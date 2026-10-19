#!/usr/bin/env python3
"""아파트 매매 실거래 데이터 확인용(spike) 스크립트. 제품 코드가 아니다.

fetch   : 공공데이터포털 API로 최근 N개월 실거래를 받아 날짜별 스냅샷 폴더에 원본 XML로 저장한다.
analyze : 스냅샷 하나를 읽어 월별 건수, 항목별 빈 비율, 식별 키 겹침, 해제 건수를 표로 낸다.

표준 라이브러리만 쓰고 Python 3.9에서 돈다.
"""
import argparse
import collections
import datetime
import http.client
import json
import math
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ENDPOINT = ("https://apis.data.go.kr/1613000/RTMSDataSvcAptTradeDev/"
            "getRTMSDataSvcAptTradeDev")
DEFAULT_ENV = Path.home() / "Desktop" / "real-estate" / ".env"
DEFAULT_OUT = Path.home() / "Desktop" / "real-estate" / ".private" / "data" / "snapshots"
NUM_OF_ROWS = 1000
REQUEST_GAP_SEC = 0.2
RETRY_DELAYS = (2, 4)  # 첫 시도 실패 뒤 2초, 두 번째 실패 뒤 4초. 최대 3번 시도한다.
TIMEOUT_SEC = 30

FIELDS = [
    "aptDong", "aptNm", "aptSeq", "bonbun", "bubun", "buildYear", "buyerGbn",
    "cdealDay", "cdealType", "dealAmount", "dealDay", "dealMonth", "dealYear",
    "dealingGbn", "estateAgentSggNm", "excluUseAr", "floor", "jibun", "landCd",
    "landLeaseholdGbn", "rgstDate", "roadNm", "roadNmBonbun", "roadNmBubun",
    "roadNmCd", "roadNmSeq", "roadNmSggCd", "roadNmbCd", "sggCd", "slerGbn",
    "umdCd", "umdNm",
]
KEY_BASE = ["aptSeq", "dealYear", "dealMonth", "dealDay", "floor",
            "excluUseAr", "dealAmount"]


class FetchError(Exception):
    """한 달을 받다가 실패한 이유. retryable이면 같은 요청을 다시 시도한다."""

    def __init__(self, message, retryable=False, http_status=None, result_code=None):
        super().__init__(message)
        self.retryable = retryable
        self.http_status = http_status
        self.result_code = result_code


def load_env(path):
    values = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            v = v.strip()
            if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
                v = v[1:-1]
            values[k.strip()] = v.strip()
    return values


def read_credentials(env_path):
    """(key, lawd) 또는 종료 코드 2로 끝낸다. 값은 어디에도 찍지 않는다."""
    try:
        env = load_env(env_path)
    except OSError as e:
        print("환경 파일을 읽을 수 없다: %s (%s)" % (env_path, e.strerror), file=sys.stderr)
        sys.exit(2)
    missing = [k for k in ("DATA_GO_KR_SERVICE_KEY", "LAWD_CD") if not env.get(k)]
    if missing:
        print("환경 파일에 없는 항목: %s" % ", ".join(missing), file=sys.stderr)
        sys.exit(2)
    return env["DATA_GO_KR_SERVICE_KEY"], env["LAWD_CD"]


def make_redactor(*secrets):
    """오류 메시지에서 인증키와 지역 코드를 가린다."""
    pieces = set()
    for s in secrets:
        pieces.add(s)
        pieces.add(urllib.parse.quote(s, safe=""))
        pieces.add(urllib.parse.quote_plus(s))
    ordered = sorted((p for p in pieces if p), key=len, reverse=True)

    def redact(text):
        text = str(text)
        for p in ordered:
            text = text.replace(p, "***")
        return text

    return redact


def parse_date_dir(name):
    """폴더 이름이 달력상 유효한 YYYY-MM-DD면 date를, 아니면 None을 돌려준다."""
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", name):
        return None
    try:
        return datetime.date.fromisoformat(name)
    except ValueError:
        return None


def month_list(date, n):
    out = []
    y, m = date.year, date.month
    for _ in range(n):
        out.append("%04d%02d" % (y, m))
        m -= 1
        if m == 0:
            y, m = y - 1, 12
    return out


def parse_page(body):
    """응답 XML을 (totalCount, 건수)로 읽는다. 정상 코드가 아니면 FetchError."""
    try:
        root = ET.fromstring(body)
    except ET.ParseError as e:
        raise FetchError("XML 해석 실패: %s" % e)
    code = root.findtext("header/resultCode")
    msg = root.findtext("header/resultMsg")
    if code is None:
        # 포털 공통 오류 응답(OpenAPI_ServiceResponse)
        code = root.findtext(".//returnReasonCode")
        msg = root.findtext(".//returnAuthMsg") or root.findtext(".//errMsg")
        if code is None:
            raise FetchError("알 수 없는 응답 구조(루트 %s)" % root.tag)
    code = code.strip()
    if code != "000":
        extra = " (일일 호출 한도 초과)" if code == "22" else ""
        raise FetchError("resultCode %s %s%s" % (code, (msg or "").strip(), extra),
                         result_code=code)
    raw_total = (root.findtext("body/totalCount") or "0").strip()
    try:
        total = int(raw_total)
    except ValueError:
        raise FetchError("totalCount가 숫자가 아니다: %r" % raw_total)
    count = len(root.findall("body/items/item"))
    return code, total, count


def request_page(key, lawd, ymd, page, stats):
    """한 페이지를 받는다. HTTP/네트워크 오류만 다시 시도한다."""
    query = urllib.parse.urlencode({
        "serviceKey": key, "LAWD_CD": lawd, "DEAL_YMD": ymd,
        "pageNo": page, "numOfRows": NUM_OF_ROWS,
    })
    url = ENDPOINT + "?" + query
    attempt = 0
    while True:
        attempt += 1
        if stats["calls"] > 0:
            time.sleep(REQUEST_GAP_SEC)
        stats["calls"] += 1
        try:
            with urllib.request.urlopen(url, timeout=TIMEOUT_SEC) as resp:
                status = resp.status
                body = resp.read()
            return status, body
        except urllib.error.HTTPError as e:
            err = FetchError("HTTP %s %s" % (e.code, e.reason), True, http_status=e.code)
        except (urllib.error.URLError, OSError, http.client.HTTPException) as e:
            reason = getattr(e, "reason", e)
            err = FetchError("네트워크 오류: %s" % reason, True)
        if attempt > len(RETRY_DELAYS):
            raise err
        time.sleep(RETRY_DELAYS[attempt - 1])


def fetch_month(key, lawd, ymd, stats):
    """한 달을 끝까지 받아 (페이지 본문 목록, 기록)을 돌려준다. 실패하면 FetchError."""
    started = time.time()
    calls_before = stats["calls"]
    rec = {"month": ymd, "ok": False, "result_code": None, "http_status": None,
           "pages": 0, "received": 0, "total_count": None}
    bodies = []
    try:
        page = 1
        total_pages = 1
        while page <= total_pages:
            status, body = request_page(key, lawd, ymd, page, stats)
            rec["http_status"] = status
            code, total, count = parse_page(body)
            rec["result_code"] = code
            rec["total_count"] = total
            rec["received"] += count
            bodies.append(body)
            total_pages = max(1, int(math.ceil(total / float(NUM_OF_ROWS))))
            page += 1
        rec["pages"] = len(bodies)
        if rec["received"] != rec["total_count"]:
            raise FetchError("받은 건수 %d가 totalCount %d와 다르다"
                             % (rec["received"], rec["total_count"]))
        rec["ok"] = True
    except FetchError as e:
        rec["error"] = e
        if e.http_status is not None:
            rec["http_status"] = e.http_status
        if e.result_code is not None:
            rec["result_code"] = e.result_code
    except Exception as e:
        rec["error"] = FetchError("예상하지 못한 오류: %s" % e)
    finally:
        rec["elapsed_sec"] = round(time.time() - started, 2)
        rec["calls"] = stats["calls"] - calls_before
    return bodies, rec


def save_month(snap_dir, ymd, bodies):
    """같은 달의 옛 파일을 지우고 새로 쓴다. 한 페이지면 YYYYMM.xml, 아니면 YYYYMM-pN.xml."""
    for old in snap_dir.glob(ymd + "*.xml"):
        old.unlink()
    if len(bodies) == 1:
        (snap_dir / (ymd + ".xml")).write_bytes(bodies[0])
    else:
        for i, body in enumerate(bodies, 1):
            (snap_dir / ("%s-p%d.xml" % (ymd, i))).write_bytes(body)


def cmd_fetch(args):
    key, lawd = read_credentials(args.env)
    redact = make_redactor(key, lawd)
    date = datetime.date.fromisoformat(args.date) if args.date else datetime.date.today()
    months = month_list(date, args.months)
    snap_dir = Path(args.out) / date.isoformat()
    snap_dir.mkdir(parents=True, exist_ok=True)

    stats = {"calls": 0}
    results = []
    failed = []
    lines = []
    for ymd in months:
        bodies, rec = fetch_month(key, lawd, ymd, stats)
        err = rec.pop("error", None)
        if rec["ok"]:
            try:
                save_month(snap_dir, ymd, bodies)
            except OSError as e:
                rec["ok"] = False
                err = FetchError("저장 실패: %s" % e)
        if rec["ok"]:
            lines.append("%s  %d건" % (ymd, rec["received"]))
        else:
            msg = redact(err)
            rec["error"] = msg
            failed.append({"month": ymd, "error": msg})
            lines.append("%s  실패: %s" % (ymd, msg))
        results.append(rec)

    meta = {
        "fetched_at": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
        "date": date.isoformat(),
        "months": months,
        "total_calls": stats["calls"],
        "results": results,
        "failed": failed,
    }
    (snap_dir / "fetch.json").write_text(
        json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("\n".join(lines))
    return 1 if failed else 0


# ---------------------------------------------------------------- analyze

def is_blank(value):
    return value is None or value.strip() == ""


def read_snapshot(snap_dir):
    """(월 -> item 목록) 을 돌려준다. 같은 달의 여러 페이지 파일은 합친다."""
    by_month = collections.OrderedDict()
    for path in sorted(snap_dir.glob("*.xml")):
        ymd = path.name[:6]
        root = ET.parse(str(path)).getroot()
        items = by_month.setdefault(ymd, [])
        for node in root.findall("body/items/item"):
            items.append({c.tag: (c.text or "") for c in node})
    return by_month


def pct(part, whole):
    return "%.1f" % (100.0 * part / whole) if whole else "0.0"


def md_table(headers, rows):
    out = ["| " + " | ".join(headers) + " |", "|" + "|".join(["---"] * len(headers)) + "|"]
    for r in rows:
        out.append("| " + " | ".join(str(c) for c in r) + " |")
    return "\n".join(out)


def overlap(items, fields):
    groups = collections.Counter(tuple(i.get(f, "").strip() for f in fields) for i in items)
    dup = [n for n in groups.values() if n >= 2]
    return len(dup), sum(dup)


def build_summary(snap_dir, by_month, months_shown):
    items = [i for lst in by_month.values() for i in lst]
    total = len(items)
    name = snap_dir.name

    # 최근 N개월 창: 폴더 이름이 날짜면 그 달부터, 아니면 스냅샷의 가장 최근 달부터
    snap_date = parse_date_dir(name)
    if snap_date is not None:
        window = month_list(snap_date, months_shown)
    else:
        window = month_list(datetime.date(int(max(by_month)[:4]), int(max(by_month)[4:]), 1),
                            months_shown)
    shown = [m for m in sorted(window) if m in by_month]

    failed_text = "없음"
    meta_path = snap_dir / "fetch.json"
    if meta_path.exists():
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        if meta.get("failed"):
            failed_text = ", ".join(f["month"] for f in meta["failed"])

    months_all = sorted(by_month)
    rng = "%s ~ %s" % (months_all[0], months_all[-1]) if months_all else "없음"
    parts = [
        "# 실거래 스냅샷 집계",
        "",
        "- 스냅샷: %s" % name,
        "- 계약년월 범위: %s" % rng,
        "- 전체 건수: %d" % total,
        "- 실패한 달: %s" % failed_text,
        "",
        "### 1. 월별 건수(최근 %d개월)" % months_shown,
        "",
    ]
    rows = []
    sum_n = sum_c = 0
    for m in shown:
        n = len(by_month[m])
        c = sum(1 for i in by_month[m] if not is_blank(i.get("cdealType")))
        rows.append((m, n, c))
        sum_n += n
        sum_c += c
    rows.append(("합계", sum_n, sum_c))
    parts += [md_table(["계약년월", "건수", "해제 건수"], rows), "",
              "### 2. 항목별 빈 비율", ""]

    blank_rows = []
    for f in FIELDS:
        b = sum(1 for i in items if is_blank(i.get(f)))
        blank_rows.append((f, b, total, pct(b, total)))
    blank_rows.sort(key=lambda r: (r[1] / float(total)) if total else 0, reverse=True)
    parts += [md_table(["항목", "빈 건수", "전체", "빈 비율(%)"], blank_rows), "",
              "### 3. 식별 키 겹침", ""]

    ov_rows = []
    for label, fields in (("+".join(KEY_BASE), KEY_BASE),
                          ("+".join(KEY_BASE + ["aptDong"]), KEY_BASE + ["aptDong"])):
        g, c = overlap(items, fields)
        ov_rows.append((label, g, c, total))
    parts += [md_table(["키 구성", "겹치는 묶음 수", "겹침에 걸린 건수", "전체 건수"], ov_rows), "",
              "### 4. 해제와 등기", ""]

    def cnt(field):
        return sum(1 for i in items if not is_blank(i.get(field)))

    c4 = []
    for label, field in (("해제 표시(cdealType 비어 있지 않음)", "cdealType"),
                         ("해제 사유 발생일 있음(cdealDay 비어 있지 않음)", "cdealDay"),
                         ("등기일 있음(rgstDate 비어 있지 않음)", "rgstDate"),
                         ("동 있음(aptDong 비어 있지 않음)", "aptDong")):
        n = cnt(field)
        c4.append((label, n, pct(n, total)))
    c4.append(("전체", total, pct(total, total)))
    parts += [md_table(["구분", "건수", "비율(%)"], c4), ""]
    return "\n".join(parts)


def cmd_analyze(args):
    if args.snapshot_dir:
        snap_dir = Path(args.snapshot_dir)
    else:
        dirs = sorted(p for p in Path(args.out).iterdir()
                      if p.is_dir() and parse_date_dir(p.name) is not None) \
            if Path(args.out).is_dir() else []
        if not dirs:
            print("스냅샷 폴더가 없다: 먼저 fetch를 실행한다", file=sys.stderr)
            return 2
        snap_dir = dirs[-1]
    if not snap_dir.is_dir():
        print("스냅샷 폴더가 아니다: %s" % snap_dir, file=sys.stderr)
        return 2
    by_month = read_snapshot(snap_dir)
    if not by_month:
        print("XML 파일이 없다: %s" % snap_dir, file=sys.stderr)
        return 2
    text = build_summary(snap_dir, by_month, args.months)
    (snap_dir / "summary.md").write_text(text, encoding="utf-8")
    print(text)
    return 0


def main():
    p = argparse.ArgumentParser(description="실거래 데이터 확인용 스크립트")
    sub = p.add_subparsers(dest="cmd", required=True)

    f = sub.add_parser("fetch", help="API로 받아 날짜별 스냅샷에 저장")
    f.add_argument("--env", default=DEFAULT_ENV,
                   help="기본값 ~/Desktop/real-estate/.env")
    f.add_argument("--out", default=DEFAULT_OUT,
                   help="기본값 ~/Desktop/real-estate/.private/data/snapshots")
    f.add_argument("--months", type=int, default=6)
    f.add_argument("--date", help="YYYY-MM-DD, 기본값은 오늘")
    f.set_defaults(func=cmd_fetch)

    a = sub.add_parser("analyze", help="스냅샷 하나를 집계")
    a.add_argument("snapshot_dir", nargs="?")
    a.add_argument("--out", default=DEFAULT_OUT,
                   help="기본값 ~/Desktop/real-estate/.private/data/snapshots")
    a.add_argument("--months", type=int, default=12)
    a.set_defaults(func=cmd_analyze)

    args = p.parse_args()
    sys.exit(args.func(args))


if __name__ == "__main__":
    main()
