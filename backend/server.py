# server.py
from flask import Flask, request, jsonify
from flask_cors import CORS
import torch, joblib, numpy as np, io, os, traceback, json, sqlite3
import soundfile as sf
import librosa
from datetime import datetime, timezone

# ======================
# 설정
# ====================== 
ALLOWED_EXT = {".wav", ".mp3"}
TARGET_SR = 16000
N_MFCC = 13

app = Flask(__name__)
CORS(app)

device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
3
# ======================
# 경로
# ======================
ROOT = os.path.dirname(os.path.abspath(__file__))
MODEL_PATH  = os.path.join(ROOT, "best_asvspoof_model_cuda.pt")
SCALER_PATH = os.path.join(ROOT, "scaler_asvspoof.pkl")
DB_PATH     = os.path.join(ROOT, "deepfake.db")

# 시연용 번호 풀(순환)
PHONE_POOL_PATH   = os.path.join(ROOT, "data.json")
PHONE_CURSOR_PATH = os.path.join(ROOT, "phone_cursor.txt")

# ======================
# 모델 정의
# ======================
class SEBlock(torch.nn.Module):
    def __init__(self, channels, reduction=8):
        super().__init__()
        self.fc1 = torch.nn.Linear(channels, channels // reduction)
        self.fc2 = torch.nn.Linear(channels // reduction, channels)
    def forward(self, x):
        y = torch.relu(self.fc1(x))
        y = torch.sigmoid(self.fc2(y))
        return x * y

class AttentionAudioClassifier(torch.nn.Module):
    def __init__(self, input_dim, num_classes=3):
        super().__init__()
        self.fc1 = torch.nn.Sequential(
            torch.nn.Linear(input_dim, 512),
            torch.nn.BatchNorm1d(512),
            torch.nn.ReLU(),
            torch.nn.Dropout(0.4),
        )
        self.se1 = SEBlock(512)
        self.fc2 = torch.nn.Sequential(
            torch.nn.Linear(512, 384),
            torch.nn.BatchNorm1d(384),
            torch.nn.ReLU(),
            torch.nn.Dropout(0.35),
        )
        self.se2 = SEBlock(384)
        self.fc3 = torch.nn.Sequential(
            torch.nn.Linear(384, 256),
            torch.nn.BatchNorm1d(256),
            torch.nn.ReLU(),
            torch.nn.Dropout(0.3),
        )
        self.se3 = SEBlock(256)
        self.classifier = torch.nn.Sequential(
            torch.nn.Linear(256, 128),
            torch.nn.BatchNorm1d(128),
            torch.nn.ReLU(),
            torch.nn.Dropout(0.3),
            torch.nn.Linear(128, num_classes),
        )
    def forward(self, x):
        x = self.fc1(x); x = self.se1(x)
        x = self.fc2(x); x = self.se2(x)
        x = self.fc3(x); x = self.se3(x)
        return self.classifier(x)

# ======================
# 모델/스케일러 로드
# ======================
model = AttentionAudioClassifier(input_dim=N_MFCC, num_classes=3).to(device)
model.load_state_dict(torch.load(MODEL_PATH, map_location=device))
model.eval()
scaler = joblib.load(SCALER_PATH)
class_names = ["real", "fake_2", "tts"]

# ======================
# DB 유틸/마이그레이션
# ======================
def _utcnow_str():
    return datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%S')

def _clamp(v: float, lo: float = 0.0, hi: float = 1.0) -> float:
    try:
        v = float(v)
    except Exception:
        v = lo
    return max(lo, min(hi, v))

def _conn():
    # WAL + busy_timeout으로 "database is locked" 완화
    conn = sqlite3.connect(DB_PATH, timeout=10, isolation_level=None)  # autocommit
    cur = conn.cursor()
    cur.execute("PRAGMA journal_mode=WAL;")
    cur.execute("PRAGMA synchronous=NORMAL;")
    cur.execute("PRAGMA busy_timeout=10000;")
    cur.execute("PRAGMA foreign_keys=ON;")
    return conn

def _table_columns(cur, table):
    cur.execute(f"PRAGMA table_info({table})")
    return [r[1] for r in cur.fetchall()]

def init_db():
    """테이블 생성 + 스키마 자동 업그레이드"""
    conn = _conn()
    cur = conn.cursor()

    # results: 파일별 최신 판별 + 연결된 전화번호(선택)
    cur.execute("""
    CREATE TABLE IF NOT EXISTS results (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        timestamp    TEXT NOT NULL,
        filename     TEXT,
        prediction   TEXT,
        confidence   REAL,
        prob_real    REAL,
        prob_fake2   REAL,
        prob_tts     REAL,
        phone_number TEXT
    )
    """)
    cols = _table_columns(cur, "results")
    if "phone_number" not in cols:
        cur.execute("ALTER TABLE results ADD COLUMN phone_number TEXT")
    cur.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_results_filename ON results(filename)")

    # phone_reports: 번호별 누적
    cur.execute("""
    CREATE TABLE IF NOT EXISTS phone_reports (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        phone_number    TEXT UNIQUE,
        report_count    INTEGER DEFAULT 0,
        last_confidence REAL,
        risk_score      REAL,
        ema_alpha       REAL,
        updated_at      TEXT,
        created_at      TEXT
    )
    """)
    cols = _table_columns(cur, "phone_reports")
    if "report_count" not in cols:
        cur.execute("ALTER TABLE phone_reports ADD COLUMN report_count INTEGER DEFAULT 0")
    if "last_confidence" not in cols:
        cur.execute("ALTER TABLE phone_reports ADD COLUMN last_confidence REAL")
    if "risk_score" not in cols:
        cur.execute("ALTER TABLE phone_reports ADD COLUMN risk_score REAL")
    if "ema_alpha" not in cols:
        cur.execute("ALTER TABLE phone_reports ADD COLUMN ema_alpha REAL")
    if "updated_at" not in cols:
        cur.execute("ALTER TABLE phone_reports ADD COLUMN updated_at TEXT")
    if "created_at" not in cols:
        cur.execute("ALTER TABLE phone_reports ADD COLUMN created_at TEXT")

    now = _utcnow_str()
    cur.execute("UPDATE phone_reports SET report_count = COALESCE(report_count, 0)")
    cur.execute("UPDATE phone_reports SET ema_alpha   = COALESCE(ema_alpha, 0.3)")
    cur.execute("UPDATE phone_reports SET updated_at  = COALESCE(updated_at, ?)", (now,))
    cur.execute("UPDATE phone_reports SET created_at  = COALESCE(created_at, ?)", (now,))

    conn.commit()
    conn.close()

def save_result(filename: str, pred_label: str, conf: float, probs_np, phone_number: str | None):
    """results에 upsert(파일당 1행)하고 row id 반환"""
    ts = _utcnow_str()
    prob_real, prob_fake2, prob_tts = float(probs_np[0]), float(probs_np[1]), float(probs_np[2])

    conn = _conn()
    cur = conn.cursor()
    cur.execute("""
        INSERT INTO results (timestamp, filename, prediction, confidence, prob_real, prob_fake2, prob_tts, phone_number)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(filename) DO UPDATE SET
            timestamp    = excluded.timestamp,
            prediction   = excluded.prediction,
            confidence   = excluded.confidence,
            prob_real    = excluded.prob_real,
            prob_fake2   = excluded.prob_fake2,
            prob_tts     = excluded.prob_tts,
            phone_number = excluded.phone_number
    """, (ts, filename, pred_label, conf, prob_real, prob_fake2, prob_tts, phone_number))
    cur.execute("SELECT id FROM results WHERE filename = ?", (filename,))
    row = cur.fetchone()
    rowid = row[0] if row else None
    conn.commit()
    conn.close()
    return rowid

def upsert_phone_report(phone_number: str, confidence: float):
    """
    EMA + 동적 α 로 위험도 갱신:
      alpha = 0.3 + 0.7*(confidence - 0.5)  → 0~1로 클램프
      old_risk 없으면 conf로 초기화
      risk_new = alpha*conf + (1-alpha)*old_risk
    """
    now = _utcnow_str()
    conf = _clamp(confidence)

    conn = _conn()
    cur  = conn.cursor()
    cur.execute("""
        SELECT id, report_count, risk_score
          FROM phone_reports
         WHERE phone_number = ?
    """, (phone_number,))
    row = cur.fetchone()

    # 동적 alpha (신뢰도 높을수록 더 빠르게 반영)
    alpha = _clamp(0.3 + 0.7 * (conf - 0.5))

    if row:
        _id, count, old_risk = row[0], (row[1] or 0), row[2]
        base = conf if old_risk is None else float(old_risk)
        new_risk = _clamp(alpha * conf + (1.0 - alpha) * base)
        cur.execute("""
            UPDATE phone_reports
               SET report_count    = ?,
                   last_confidence = ?,
                   risk_score      = ?,
                   ema_alpha       = ?,
                   updated_at      = ?
             WHERE id = ?
        """, (count + 1, conf, new_risk, alpha, now, _id))
    else:
        # 최초 행: risk = conf 시작
        new_risk = conf
        cur.execute("""
            INSERT INTO phone_reports
                (phone_number, report_count, last_confidence, risk_score, ema_alpha, updated_at, created_at)
            VALUES
                (?,            1,           ?,               ?,          ?,         ?,          ?)
        """, (phone_number, conf, new_risk, alpha, now, now))

    conn.commit()
    conn.close()

# ======================
# 번호 풀 (data.json) 순환 – 시연용
# ======================
def _load_phone_pool():
    try:
        with open(PHONE_POOL_PATH, "r", encoding="utf-8") as f:
            data = json.load(f)
        pool = []
        for item in data:
            if isinstance(item, str):
                pool.append(item.strip())
            elif isinstance(item, dict) and "phone_number" in item:
                pool.append(str(item["phone_number"]).strip())
        return [p for p in pool if p]
    except Exception:
        return []

def _read_cursor(max_len: int) -> int:
    try:
        with open(PHONE_CURSOR_PATH, "r", encoding="utf-8") as f:
            v = int(f.read().strip())
            return v if 0 <= v < max_len else 0
    except Exception:
        return 0

def _write_cursor(v: int):
    try:
        with open(PHONE_CURSOR_PATH, "w", encoding="utf-8") as f:
            f.write(str(v))
    except Exception:
        pass

def get_next_phone_number():
    pool = _load_phone_pool()
    if not pool:
        return None
    idx = _read_cursor(len(pool))
    number = pool[idx]
    _write_cursor((idx + 1) % len(pool))
    return number

# ======================
# 오디오 유틸
# ======================
def load_audio_wav_or_mp3(audio_bytes: bytes, filename: str, target_sr=TARGET_SR) -> np.ndarray:
    ext = os.path.splitext(filename.lower())[1]
    if ext not in ALLOWED_EXT:
        raise ValueError(f"Only WAV/MP3 allowed, got: {ext}")
    try:
        if ext == ".wav":
            y, sr = sf.read(io.BytesIO(audio_bytes), always_2d=False)
        else:
            y, sr = librosa.load(io.BytesIO(audio_bytes), sr=None, mono=False)
    except Exception as e:
        raise RuntimeError(f"Audio decode failed: {e}")
    if isinstance(y, np.ndarray) and y.ndim == 2:
        y = y.mean(axis=1)
    y = y.astype(np.float32)
    if sr != target_sr:
        y = librosa.resample(y=y, orig_sr=sr, target_sr=target_sr)
    min_len = int(0.3 * target_sr)
    max_len = int(10.0 * target_sr)
    if y.size < min_len:
        raise ValueError("Audio too short (<0.3s)")
    if y.size > max_len:
        y = y[:max_len]
    else:
        y = np.pad(y, (0, max_len - y.size))
    return y

def extract_mfcc_from_bytes(audio_bytes: bytes, filename: str, sr=TARGET_SR, n_mfcc=N_MFCC) -> np.ndarray:
    y = load_audio_wav_or_mp3(audio_bytes, filename, target_sr=sr)
    mfcc = librosa.feature.mfcc(y=y, sr=sr, n_mfcc=n_mfcc)
    return np.mean(mfcc, axis=1).astype(np.float32)

# ======================
# 엔드포인트
# ======================
@app.get("/health")
def health():
    return jsonify({"status": "ok"})

@app.post("/predict")
def predict():
    try:
        file = request.files.get("audio")
        if not file:
            return jsonify({"error": 'No audio (field "audio" required)'}), 400

        filename = file.filename or "unknown"
        audio_bytes = file.read()
        if not audio_bytes:
            return jsonify({"error": "Empty file"}), 400

        # 1) 추론
        mfcc = extract_mfcc_from_bytes(audio_bytes, filename)
        mfcc_scaled = scaler.transform(mfcc.reshape(1, -1))
        x = torch.from_numpy(mfcc_scaled).float().to(device)

        with torch.no_grad():
            logits = model(x)
            probs = torch.softmax(logits, dim=1)[0]
            conf, idx = torch.max(probs, dim=0)

        pred_label = class_names[int(idx)]
        conf_f     = float(conf)
        probs_np   = probs.detach().cpu().numpy()

        # 2) 저장 여부(사용자 확인 기반 2단계)
        confirm_flag = (request.form.get("confirm") or request.args.get("confirm") or "0")
        is_confirmed = str(confirm_flag).lower() in ("1", "true", "yes")

        saved_id = None
        phone_number = None

        if is_confirmed and pred_label.lower() != "real":
            # 확정 저장: 번호가 없으면 풀에서 자동 할당
            phone_number = request.form.get("phone_number") or request.args.get("phone_number")
            if not phone_number:
                phone_number = get_next_phone_number()

            saved_id = save_result(filename, pred_label, conf_f, probs_np, phone_number)

            # 번호 누적(EMA 위험도 포함)
            if phone_number:
                upsert_phone_report(phone_number, conf_f)

            saved = True
        else:
            # 미확정(dry-run): 저장하지 않음
            saved = False

        return jsonify({
            "id": saved_id,
            "saved": saved,
            "prediction": pred_label,
            "confidence": conf_f,
            "probabilities": {
                "real":  float(probs_np[0]),
                "fake_2":float(probs_np[1]),
                "tts":   float(probs_np[2]),
            },
            "phone_number": phone_number
        }), 200

    except Exception as e:
        print("=== /predict ERROR ===")
        print(traceback.format_exc())
        status = 415 if "Only WAV/MP3 allowed" in str(e) else 500
        return jsonify({"error": str(e)}), status

# ======================
# 실행
# ======================
if __name__ == "__main__":
    print(f"✅ Using DB: {DB_PATH}")
    init_db()
    app.run(host="0.0.0.0", port=5000, debug=False)
