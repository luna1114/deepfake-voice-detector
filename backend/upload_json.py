# upload_json.py
import sys, json, sqlite3, os
from datetime import datetime, timezone

# ======================
# 경로 설정: server.py와 같은 폴더의 deepfake.db 사용
# ======================
ROOT = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.path.join(ROOT, "deepfake.db")  # server.py와 동일

# ======================
# 테이블 자동 생성 (server.py와 동일한 구조로)
# ======================
def init_db():
    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    # 기존 server.py results 테이블 유지
    cur.execute("""
    CREATE TABLE IF NOT EXISTS results (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        timestamp TEXT NOT NULL,
        filename TEXT,
        prediction TEXT,
        confidence REAL,
        prob_real REAL,
        prob_fake2 REAL,
        prob_tts REAL
    )
    """)
    # 전화번호용 새 테이블 추가
    cur.execute("""
    CREATE TABLE IF NOT EXISTS phone_reports (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        created_at TEXT NOT NULL,
        phone_number TEXT NOT NULL,
        confidence REAL NOT NULL
    )
    """)
    conn.commit()
    conn.close()

# ======================
# 업로드 함수
# ======================
def upload_from_json(path):
    if not os.path.exists(path):
        print(f"❌ 파일을 찾을 수 없습니다: {path}")
        return

    # JSON 로드
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except Exception as e:
        print(f"❌ JSON 파싱 오류: {e}")
        return

    # 리스트/단일 객체 모두 허용
    if isinstance(data, dict):
        data = [data]
    elif not isinstance(data, list):
        print("❌ JSON 형식이 잘못되었습니다. 리스트 또는 객체를 넣어주세요.")
        return

    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    now = datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%S')
    count = 0

    for item in data:
        phone = str(item.get("phone_number", "")).strip()
        conf = item.get("confidence", None)
        if not phone or conf is None:
            print(f"⚠️ 스킵됨 (phone_number/confidence 누락): {item}")
            continue
        try:
            cur.execute(
                "INSERT INTO phone_reports (created_at, phone_number, confidence) VALUES (?, ?, ?)",
                (now, phone, float(conf))
            )
            count += 1
        except Exception as e:
            print(f"⚠️ DB 삽입 오류: {e}")

    conn.commit()
    conn.close()
    print(f"✅ {count}개의 데이터가 {DB_PATH} 에 저장되었습니다.")

# ======================
# 실행부
# ======================
if __name__ == "__main__":
    if len(sys.argv) < 3 or sys.argv[1] != "upload":
        print("사용법: python upload_json.py upload data.json")
        sys.exit(1)

    json_path = sys.argv[2]
    init_db()
    upload_from_json(json_path)
