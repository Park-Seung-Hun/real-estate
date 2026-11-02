#!/usr/bin/env python3
"""아파트 매매 실거래 데이터 확인용(spike) 스크립트. 제품 코드가 아니다.

fetch   : 공공데이터포털 API로 최근 N개월 실거래를 받아 날짜별 스냅샷 폴더에 원본 XML로 저장한다.
analyze : 스냅샷 하나를 읽어 월별 건수, 항목별 빈 비율, 식별 키 겹침, 해제 건수를 표로 낸다.
compare : 날짜별 스냅샷을 서로 비교해 신규/해제/사라짐과 등기 시점을 마크다운 보고서로 낸다.

표준 라이브러리만 쓰고 Python 3.9에서 돈다.
"""
import argparse
import collections
import datetime
import http.client
import json
import math
import re
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ENDPOINT = ("https://apis.data.go.kr/1613000/RTMSDataSvcAptTradeDev/"
            "getRTMSDataSvcAptTradeDev")


def repo_root():
    """스크립트 위치에서 저장소 루트를 구한다. 워크트리 안이면 원래 체크아웃을 돌려준다."""
    root = Path(__file__).resolve().parents[2]
    if root.parent.name == ".worktrees":
        return root.parents[1]
    return root


DEFAULT_ENV = repo_root() / ".env"
DEFAULT_OUT = repo_root() / ".private" / "data" / "snapshots"
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

# ---------------------------------------------------------------- compare

CHANGE_FIELDS = ["rgstDate", "aptDong", "cdealDay", "dealingGbn", "buyerGbn",
                 "slerGbn", "estateAgentSggNm"]
DELAY_BINS = [(0, 9, "0~9"), (10, 19, "10~19"), (20, 29, "20~29"),
              (30, 59, "30~59"), (60, 89, "60~89"), (90, None, "90 이상")]
STAT_HEADERS = ["구분", "건수", "중앙값(일)", "90번째 백분위수(일)", "최댓값(일)", "최솟값(일)"]


def parse_yymmdd(text):
    """'YY.MM.DD'를 date로 읽는다. 비었거나 형식이 다르면 None."""
    m = re.fullmatch(r"(\d{2})\.(\d{2})\.(\d{2})", (text or "").strip())
    if not m:
        return None
    try:
        return datetime.date(2000 + int(m.group(1)), int(m.group(2)), int(m.group(3)))
    except ValueError:
        return None


def contract_date(item):
    try:
        return datetime.date(int(item["dealYear"]), int(item["dealMonth"]), int(item["dealDay"]))
    except (KeyError, ValueError):
        return None


def nearest_rank(values, p):
    s = sorted(values)
    return s[max(1, int(math.ceil(p / 100.0 * len(s)))) - 1]


def stat_cells(values):
    """(건수, 중앙값, 90번째 백분위수, 최댓값, 최솟값). 값이 없으면 '-'."""
    if not values:
        return [0, "-", "-", "-", "-"]
    med = statistics.median(values)
    med = int(med) if med == int(med) else med
    return [len(values), med, nearest_rank(values, 90), max(values), min(values)]


def month_end(ymd, add):
    """ymd(YYYYMM)에서 add개월 뒤 달의 말일."""
    idx = int(ymd[:4]) * 12 + int(ymd[4:]) - 1 + add
    y, m = divmod(idx, 12)
    nxt = datetime.date(y + (m + 1) // 12, (m + 1) % 12 + 1, 1)
    return nxt - datetime.timedelta(days=1)


def trade_key(item):
    base = tuple(item.get(f, "").strip() for f in KEY_BASE)
    return base + (not is_blank(item.get("cdealType")),)


def load_effective(snap_dir):
    """(월 -> item 목록, 실패한 달 목록). 그날 fetch.json의 failed에 든 달은 뺀다."""
    failed = []
    meta_path = snap_dir / "fetch.json"
    if meta_path.exists():
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        failed = [f["month"] for f in meta.get("failed", [])]
    by_month = read_snapshot(snap_dir)
    return {m: v for m, v in by_month.items() if m not in failed}, failed


def compare_pair(prev, cur, snap_date, first_date, acc, month_row):
    """같은 달의 앞뒤 스냅샷 item 목록을 비교해 acc와 month_row에 쌓는다."""
    rows_prev, rows_cur = collections.defaultdict(list), collections.defaultdict(list)
    for i in prev:
        rows_prev[trade_key(i)].append(i)
    for i in cur:
        rows_cur[trade_key(i)].append(i)
    vanished = collections.Counter()  # 해제 아닌 키의 사라진 건수, 해제 짝 판정용
    cancel_new = []                   # (키, 새로 생긴 건수)
    for key in set(rows_prev) | set(rows_cur):
        pa, pb = rows_prev.get(key, []), rows_cur.get(key, [])
        a, b, cancel = len(pa), len(pb), key[-1]
        if b > a:
            n = b - a
            c_date = contract_date(pb[0])
            delay = (snap_date - c_date).days if c_date else None
            if cancel:
                month_row["해제"] += n
                cancel_new.append((key, n))
                if delay is not None:
                    acc["cancel_from_contract"] += [delay] * n
                for r in pb[:n]:
                    cd = parse_yymmdd(r.get("cdealDay"))
                    if cd:
                        acc["cancel_from_event"].append((snap_date - cd).days)
            else:
                month_row["신규"] += n
                if delay is not None:
                    group = "after" if c_date >= first_date else "before"
                    acc["new_" + group] += [delay] * n
        elif b < a:
            month_row["사라짐"] += a - b
            if not cancel:
                vanished[key[:-1]] += a - b
            else:
                acc["vanished_unpaired"] += a - b
        elif a >= 1:
            blank = lambda rows, f: sum(1 for r in rows if is_blank(r.get(f)))
            month_row["등기일 채워짐"] += max(0, blank(pa, "rgstDate") - blank(pb, "rgstDate"))
            month_row["동 채워짐"] += max(0, blank(pa, "aptDong") - blank(pb, "aptDong"))
            if a == 1:
                for f in CHANGE_FIELDS:
                    x, y = pa[0].get(f, "").strip(), pb[0].get(f, "").strip()
                    if x and y and x != y:
                        month_row["값 바뀜"] += 1
    for key, n in cancel_new:
        paired = min(n, vanished[key[:-1]])
        vanished[key[:-1]] -= paired
        acc["pair_removed"] += paired
        acc["pair_only"] += n - paired
    acc["vanished_unpaired"] += sum(vanished.values())


def delay_row(label, values):
    return [label] + stat_cells(values)


def build_compare(out_dir, dirs):
    snaps = []  # (date, 월 -> items, 실패한 달)
    for d in dirs:
        by_month, failed = load_effective(d)
        snaps.append((parse_date_dir(d.name), by_month, failed))
    first_date, last_date = snaps[0][0], snaps[-1][0]
    acc = collections.defaultdict(list)
    for k in ("pair_removed", "pair_only", "vanished_unpaired"):
        acc[k] = 0
    month_rows = collections.defaultdict(lambda: collections.Counter())

    # 1. 스냅샷
    snap_rows = []
    for date, by_month, failed in snaps:
        ms = sorted(by_month)
        snap_rows.append((date.isoformat(), len(ms), "%s ~ %s" % (ms[0], ms[-1]) if ms else "-",
                          ", ".join(failed) if failed else "없음"))
    have = {s[0] for s in snaps}
    gaps = [(first_date + datetime.timedelta(days=n)).isoformat()
            for n in range((last_date - first_date).days + 1)
            if first_date + datetime.timedelta(days=n) not in have]

    # 비교
    series = collections.defaultdict(list)  # 월 -> [(date, items)]
    for date, by_month, _ in snaps:
        for m, items in by_month.items():
            series[m].append((date, items))
    for m, lst in series.items():
        month_rows[m]["스냅샷"] = len(lst)
        month_rows[m]["첫"] = len(lst[0][1])
        month_rows[m]["마지막"] = len(lst[-1][1])
        for (_, prev), (date, cur) in zip(lst, lst[1:]):
            compare_pair(prev, cur, date, first_date, acc, month_rows[m])

    parts = ["# 스냅샷 비교", "", "## 스냅샷", "",
             md_table(["날짜", "받은 달 수", "받은 달 범위", "실패한 달"], snap_rows), "",
             "폴더가 없는 날짜: %s" % (", ".join(gaps) if gaps else "없음"), ""]

    # 2. 계약월별 변화
    cols = ["신규", "해제", "사라짐", "등기일 채워짐", "동 채워짐", "값 바뀜"]
    rows, total = [], collections.Counter()
    for m in sorted(series):
        r = month_rows[m]
        if r["스냅샷"] < 2:
            continue
        rows.append([m, r["스냅샷"], r["첫"], r["마지막"]] + [r[c] for c in cols])
        for c in ["스냅샷", "첫", "마지막"] + cols:
            total[c] += r[c]
    rows.append(["합계", total["스냅샷"], total["첫"], total["마지막"]] + [total[c] for c in cols])
    parts += ["## 계약월별 변화", "",
              md_table(["계약년월", "비교한 스냅샷 수", "첫 스냅샷 건수", "마지막 스냅샷 건수"] + cols, rows), ""]

    # 3. 신규 지연
    allnew = acc["new_after"] + acc["new_before"]
    bins = []
    for lo, hi, label in DELAY_BINS:
        bins.append((label, sum(1 for v in allnew if v >= lo and (hi is None or v <= hi))))
    parts += ["## 신규 지연", "",
              md_table(STAT_HEADERS, [
                  delay_row("첫 스냅샷 날짜 이후 계약", acc["new_after"]),
                  delay_row("그 전 계약", acc["new_before"]),
                  delay_row("전체", allnew)]), "",
              md_table(["구간(일)", "건수"], bins), ""]

    # 4. 해제
    parts += ["## 해제", "",
              md_table(STAT_HEADERS, [
                  delay_row("계약일부터 나타날 때까지", acc["cancel_from_contract"]),
                  delay_row("해제사유발생일부터 나타날 때까지", acc["cancel_from_event"])]), "",
              md_table(["구분", "건수"], [
                  ("해제 전 행이 같은 날 빠짐", acc["pair_removed"]),
                  ("해제 행만 나타남", acc["pair_only"]),
                  ("해제 짝 없는 사라짐", acc["vanished_unpaired"])]), ""]

    # 5. 등기와 해제 시점
    ref_date, ref_months = max(((d, bm) for d, bm, _ in snaps), key=lambda x: (len(x[1]), -x[0].toordinal()))
    cutoff = ref_date - datetime.timedelta(days=365)
    targets = []  # (계약월, 계약일, 등기일, 해제사유발생일)
    for m, items in ref_months.items():
        for i in items:
            c = contract_date(i)
            if c and c < cutoff:
                targets.append((m, c, parse_yymmdd(i.get("rgstDate")), parse_yymmdd(i.get("cdealDay"))))
    rg = [(m, c, r) for m, c, r, _ in targets if r]
    cd = [(m, c, x) for m, c, _, x in targets if x]
    rg_days = [(r - c).days for _, c, r in rg]
    cd_days = [(x - c).days for _, c, x in cd]
    ta = []
    for label, days in (("등기일", rg_days), ("해제사유발생일", cd_days)):
        cells = stat_cells(days)
        ta.append([label, len(targets), cells[0], cells[1], cells[2], cells[3]])
    tb = []
    for t in (30, 60, 90, 120, 150, 180, 270, 365):
        tb.append([t, pct(sum(1 for v in rg_days if v > t), len(rg_days)),
                   pct(sum(1 for v in cd_days if v > t), len(cd_days))])
    tc = []
    for n in (3, 4, 6, 12):
        spans = [(month_end(m, n - 1) - c).days for m, c, _, _ in targets]
        miss_r = sum(1 for m, c, r in rg if r > month_end(m, n - 1))
        miss_c = sum(1 for m, c, x in cd if x > month_end(m, n - 1))
        tc.append(["%d개월" % n, "%d~%d" % (min(spans), max(spans)) if spans else "-",
                   pct(miss_r, len(rg)), pct(miss_c, len(cd))])
    parts += ["## 계약 뒤 등기와 해제가 생기는 시점", "",
              "기준 스냅샷은 %s이다(받은 달 %d개). 이 날짜보다 365일 넘게 앞선 계약 %d건을 본다."
              % (ref_date.isoformat(), len(ref_months), len(targets)), "",
              md_table(["항목", "대상 계약 건수", "값 있는 건수", "중앙값(일)",
                        "90번째 백분위수(일)", "최댓값(일)"], ta), "",
              md_table(["기준(일)", "등기일이 이보다 늦은 비율(%)",
                        "해제사유발생일이 이보다 늦은 비율(%)"], tb), "",
              md_table(["다시 받는 기간", "지켜보는 기간(일, 최소~최대)", "놓치는 등기일(%)",
                        "놓치는 해제사유발생일(%)"], tc), ""]

    # 6. 계산 방법
    parts += ["## 계산 방법", "",
              "- 거래 키는 %s에 해제 여부(`cdealType`이 비어 있지 않으면 해제)를 더한 것이다. 값은 앞뒤 공백을 지워 비교하고, 같은 키가 여러 행이면 건수로 센다." % ", ".join(KEY_BASE),
              "- 계약월마다 그 달 파일이 있는 스냅샷을 날짜순으로 놓고 바로 앞 스냅샷과 비교한다. 건수가 늘면 신규(해제 키는 해제), 줄면 사라짐이고, 같으면 등기일과 동의 빈 행 감소를 채워짐으로, 한 건짜리는 일곱 항목의 바뀐 값을 센다.",
              "- 지연은 뒤 스냅샷 날짜에서 계약일(또는 해제사유발생일)을 뺀 일수다. 백분위수는 가장 가까운 순위 방식(정렬 뒤 ceil(p/100*n)번째)이고 중앙값은 `statistics.median`이다.",
              "- 그날 `fetch.json`의 `failed`에 든 달은 파일이 남아 있어도 없는 것으로 치고, 앞 성공 스냅샷과 비교한다.",
              "- 해제 짝은 같은 날 같은 달 비교에서 해제 여부만 다른 키가 사라졌는지로 정한다. 표 B와 C의 비율 분모는 값 있는 건수이고, N개월 표의 마지막 날은 계약월 말일에서 N-1개월 뒤 달의 말일이다.",
              "- 지역 코드, 단지 일련번호, 단지 이름, 지번, 도로명, 금액은 쓰지 않는다.", ""]
    return "\n".join(parts)


def cmd_compare(args):
    out = Path(args.out)
    dirs = sorted(p for p in out.iterdir() if p.is_dir() and parse_date_dir(p.name) is not None) \
        if out.is_dir() else []
    if len(dirs) < 2:
        print("비교하려면 날짜 폴더가 2개 이상 필요하다: %s" % out, file=sys.stderr)
        return 1
    report = Path(args.report) if args.report else out / "compare.md"
    report.write_text(build_compare(out, dirs), encoding="utf-8")
    print(report)
    return 0


def main():
    p = argparse.ArgumentParser(description="실거래 데이터 확인용 스크립트")
    sub = p.add_subparsers(dest="cmd", required=True)

    f = sub.add_parser("fetch", help="API로 받아 날짜별 스냅샷에 저장")
    f.add_argument("--env", default=DEFAULT_ENV,
                   help="기본값은 저장소 루트의 .env")
    f.add_argument("--out", default=DEFAULT_OUT,
                   help="기본값은 저장소 루트의 .private/data/snapshots")
    f.add_argument("--months", type=int, default=6)
    f.add_argument("--date", help="YYYY-MM-DD, 기본값은 오늘")
    f.set_defaults(func=cmd_fetch)

    a = sub.add_parser("analyze", help="스냅샷 하나를 집계")
    a.add_argument("snapshot_dir", nargs="?")
    a.add_argument("--out", default=DEFAULT_OUT,
                   help="기본값은 저장소 루트의 .private/data/snapshots")
    a.add_argument("--months", type=int, default=12)
    a.set_defaults(func=cmd_analyze)

    c = sub.add_parser("compare", help="날짜별 스냅샷을 비교해 보고서를 씀")
    c.add_argument("--out", default=DEFAULT_OUT,
                   help="기본값은 저장소 루트의 .private/data/snapshots")
    c.add_argument("--report", help="기본값은 <out>/compare.md")
    c.set_defaults(func=cmd_compare)

    args = p.parse_args()
    sys.exit(args.func(args))


if __name__ == "__main__":
    main()
