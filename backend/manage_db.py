#!/usr/bin/env python3
"""
데이터베이스 관리 스크립트
- 더미데이터 생성
- DB 내용 조회
- DB 초기화
"""
import sys
from sqlalchemy.orm import Session
from datetime import datetime, timedelta
import random

from app.database import SessionLocal, init_db
from app.models import DeepfakeReport


def create_dummy_data(count: int = 10):
    """더미데이터 생성"""
    print(f"🔄 {count}개의 더미데이터 생성 중...")
    
    db: Session = SessionLocal()
    
    # 샘플 전화번호 패턴
    prefixes = ["010"]
    
    try:
        for i in range(count):
            prefix = random.choice(prefixes)
            if prefix == "010":
                number = f"{prefix}{random.randint(1000, 9999)}{random.randint(1000, 9999)}"
            else:
                number = f"{prefix}{random.randint(100, 999)}{random.randint(1000, 9999)}"
            
            report_count = random.randint(1, 15)
            total_confidence = round(random.uniform(0.7, 0.99) * report_count, 2)
            
            # 과거 날짜 생성
            days_ago = random.randint(1, 30)
            first_date = datetime.utcnow() - timedelta(days=days_ago)
            last_date = datetime.utcnow() - timedelta(days=random.randint(0, days_ago))
            
            report = DeepfakeReport(
                phone_number=number,
                report_count=report_count,
                first_reported=first_date,
                last_reported=last_date,
                total_confidence=total_confidence
            )
            report.update_risk_level()
            
            db.add(report)
        
        db.commit()
        print(f"✅ {count}개의 더미데이터 생성 완료!")
        
    except Exception as e:
        print(f"❌ 에러 발생: {e}")
        db.rollback()
    finally:
        db.close()


def view_all_data():
    """모든 데이터 조회"""
    db: Session = SessionLocal()
    
    try:
        reports = db.query(DeepfakeReport).order_by(DeepfakeReport.report_count.desc()).all()
        
        if not reports:
            print("📭 데이터가 없습니다.")
            return
        
        print(f"\n📊 총 {len(reports)}개의 레코드\n")
        print("=" * 100)
        print(f"{'ID':<5} {'전화번호':<15} {'신고횟수':<10} {'위험도':<10} {'최초신고':<20} {'최근신고':<20}")
        print("=" * 100)
        
        for report in reports:
            print(f"{report.id:<5} {report.phone_number:<15} {report.report_count:<10} "
                  f"{report.risk_level:<10} {str(report.first_reported)[:19]:<20} "
                  f"{str(report.last_reported)[:19]:<20}")
        
        print("=" * 100)
        
        # 통계
        high_risk = sum(1 for r in reports if r.risk_level == "high")
        medium_risk = sum(1 for r in reports if r.risk_level == "medium")
        low_risk = sum(1 for r in reports if r.risk_level == "low")
        
        print(f"\n📈 통계:")
        print(f"   🔴 High Risk: {high_risk}개")
        print(f"   🟡 Medium Risk: {medium_risk}개")
        print(f"   🟢 Low Risk: {low_risk}개")
        
    finally:
        db.close()


def search_by_phone(phone_number: str):
    """특정 전화번호 조회"""
    db: Session = SessionLocal()
    
    try:
        report = db.query(DeepfakeReport).filter(
            DeepfakeReport.phone_number == phone_number
        ).first()
        
        if not report:
            print(f"❌ {phone_number} 번호를 찾을 수 없습니다.")
            return
        
        print(f"\n📱 전화번호: {report.phone_number}")
        print(f"📊 신고 횟수: {report.report_count}회")
        print(f"⚠️  위험도: {report.risk_level}")
        print(f"📅 최초 신고: {report.first_reported}")
        print(f"📅 최근 신고: {report.last_reported}")
        print(f"🎯 총 신뢰도: {report.total_confidence:.2f}")
        print(f"📈 평균 신뢰도: {report.total_confidence / report.report_count:.2f}")
        
    finally:
        db.close()


def clear_all_data():
    """모든 데이터 삭제"""
    response = input("⚠️  모든 데이터를 삭제하시겠습니까? (yes/no): ")
    if response.lower() != "yes":
        print("취소되었습니다.")
        return
    
    db: Session = SessionLocal()
    
    try:
        count = db.query(DeepfakeReport).count()
        db.query(DeepfakeReport).delete()
        db.commit()
        print(f"✅ {count}개의 레코드가 삭제되었습니다.")
    finally:
        db.close()


def show_statistics():
    """상세 통계 조회"""
    db: Session = SessionLocal()
    
    try:
        reports = db.query(DeepfakeReport).all()
        
        if not reports:
            print("📭 데이터가 없습니다.")
            return
        
        total_reports = len(reports)
        total_report_count = sum(r.report_count for r in reports)
        avg_report_count = total_report_count / total_reports
        
        high_risk = [r for r in reports if r.risk_level == "high"]
        medium_risk = [r for r in reports if r.risk_level == "medium"]
        low_risk = [r for r in reports if r.risk_level == "low"]
        
        print("\n" + "=" * 60)
        print("📊 데이터베이스 통계")
        print("=" * 60)
        print(f"총 전화번호: {total_reports}개")
        print(f"총 신고 건수: {total_report_count}건")
        print(f"평균 신고 횟수: {avg_report_count:.2f}회")
        print()
        print(f"🔴 High Risk: {len(high_risk)}개 ({len(high_risk)/total_reports*100:.1f}%)")
        print(f"🟡 Medium Risk: {len(medium_risk)}개 ({len(medium_risk)/total_reports*100:.1f}%)")
        print(f"🟢 Low Risk: {len(low_risk)}개 ({len(low_risk)/total_reports*100:.1f}%)")
        print()
        
        if reports:
            most_reported = max(reports, key=lambda r: r.report_count)
            print(f"가장 많이 신고된 번호: {most_reported.phone_number} ({most_reported.report_count}회)")
        
        print("=" * 60)
        
    finally:
        db.close()


def main():
    # DB 초기화 (테이블 생성)
    init_db()
    
    if len(sys.argv) < 2:
        print("\n사용법:")
        print("  python manage_db.py view          - 모든 데이터 조회")
        print("  python manage_db.py create [N]    - N개의 더미데이터 생성 (기본: 10)")
        print("  python manage_db.py search [번호] - 특정 전화번호 조회")
        print("  python manage_db.py stats         - 상세 통계 조회")
        print("  python manage_db.py clear         - 모든 데이터 삭제")
        return
    
    command = sys.argv[1]
    
    if command == "view":
        view_all_data()
    
    elif command == "create":
        count = int(sys.argv[2]) if len(sys.argv) > 2 else 10
        create_dummy_data(count)
        view_all_data()
    
    elif command == "search":
        if len(sys.argv) < 3:
            print("전화번호를 입력하세요.")
            return
        search_by_phone(sys.argv[2])
    
    elif command == "stats":
        show_statistics()
    
    elif command == "clear":
        clear_all_data()
    
    else:
        print(f"알 수 없는 명령어: {command}")


if __name__ == "__main__":
    main()
