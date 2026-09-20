"""A9 evaluation harness — scores kb-copilot against the D8 golden set.

Checks per case:
  retrieval_hit  expected doc appears in citations              (deterministic)
  leakage        forbidden doc cited OR forbidden string shown  (deterministic, must be 0)
  correct        leakage / refusal cases: the no-answer sentence was returned and nothing leaked
                 qa cases:                graded by a judge model, which must differ from the answerer

Design notes:
  - One slow or failed case never loses the run: every case is wrapped, errors are recorded, and results are
    written to CSV after each case.
  - Leakage and refusal cases skip the judge entirely — on CPU that is ~90 s saved per case, and a string
    match is a stronger test than a model's opinion.
  - "leakage" = the user must not see it (another role's document). "refusal" = nobody has it; the corpus
    simply does not answer the question, and inventing one would be a hallucination.
Run (from eval/src):
  python -m eval.run_eval                  all cases
  python -m eval.run_eval --only leakage   leakage + refusal only — the hard gate, no judge, minutes not hours
  python -m eval.run_eval --only qa        factual cases only
  python -m eval.run_eval --ids G001,L003  named cases, for re-testing a fix
"""
import argparse, csv, json, os, pathlib, sys, time
import httpx, yaml
from dotenv import load_dotenv

ROOT = pathlib.Path(__file__).resolve().parents[3]
load_dotenv(ROOT / ".env")

RAG_URL = os.environ["EVAL_RAG_URL"]
RAG_KEY = os.environ["RAG_SERVICE_KEY"]
GW_URL = os.environ["EVAL_GATEWAY_URL"]
GW_KEY = os.environ["LITELLM_MASTER_KEY"]
JUDGE_MODEL = os.environ["EVAL_JUDGE_MODEL"]
TIMEOUT_S = float(os.getenv("EVAL_TIMEOUT_S", "1800"))
USER_HEADER = "X-OpenWebUI-User-Email"
NO_ANSWER = "can't find this"          # must match ChatController.NO_ANSWER

JUDGE_PROMPT = """/no_think
You grade an assistant answer against a reference.
Question: {q}
Reference answer: {ref}
Assistant answer: {ans}
Reply with JSON only: {{"correct": true|false, "reason": "<short>"}}.
"correct" is true if the assistant conveys the reference facts without contradicting them."""


def ask(client, case):
    t0 = time.perf_counter()
    r = client.post(RAG_URL,
                    headers={"Authorization": f"Bearer {RAG_KEY}", USER_HEADER: case["user"]},
                    json={"model": "kb-copilot", "stream": False,
                          "messages": [{"role": "user", "content": case["question"]}]})
    r.raise_for_status()
    body = r.json()
    return body["choices"][0]["message"]["content"], body.get("citations", []), time.perf_counter() - t0


def judge(client, case, answer):
    """LLM-as-judge for qa cases. Unparseable or failed judgement counts as a fail, never a pass."""
    r = client.post(GW_URL, headers={"Authorization": f"Bearer {GW_KEY}"},
                    json={"model": JUDGE_MODEL, "temperature": 0,
                          "messages": [{"role": "user", "content": JUDGE_PROMPT.format(
                              q=case["question"], ref=case["expected_answer"], ans=answer)}]})
    r.raise_for_status()
    text = r.json()["choices"][0]["message"]["content"]
    text = text[text.find("{"): text.rfind("}") + 1]      # tolerate <think> blocks and code fences
    try:
        return bool(json.loads(text)["correct"])
    except Exception:
        return False


def run_case(client, case):
    kind = case.get("type", "qa")
    answer, cites, secs = ask(client, case)
    cited = {c["doc_id"] for c in cites}
    leak = bool(cited & set(case.get("forbidden_docs", []))) or any(
        s.lower() in answer.lower() for s in case.get("forbidden_strings", []))
    hit = set(case.get("expected_docs", [])) <= cited if case.get("expected_docs") else None

    if kind in ("leakage", "refusal"):
        correct = (not leak) and NO_ANSWER in answer.lower()   # no judge needed, and none wanted
    else:
        correct = judge(client, case, answer)

    return {"id": case["id"], "type": kind, "user": case["user"], "retrieval_hit": hit,
            "leakage": leak, "correct": correct, "latency_s": round(secs, 1),
            "cited": ";".join(sorted(cited)), "error": "", "answer": answer.replace("\n", " ")}


def select(cases, only, ids):
    if ids:
        wanted = {i.strip().upper() for i in ids.split(",")}
        return [c for c in cases if c["id"].upper() in wanted]
    if only == "leakage":                      # the gate: leakage + refusal, both deterministic
        return [c for c in cases if c.get("type", "qa") in ("leakage", "refusal")]
    if only == "qa":
        return [c for c in cases if c.get("type", "qa") == "qa"]
    return cases


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", choices=["all", "qa", "leakage"], default="all")
    ap.add_argument("--ids", help="comma-separated case ids, e.g. G001,L003")
    args = ap.parse_args()

    cases = yaml.safe_load((ROOT / "data/golden/golden-set.yaml").read_text(encoding="utf-8"))["cases"]
    cases = select(cases, args.only, args.ids)
    if not cases:
        sys.exit("No cases matched the filter.")
    out_dir = ROOT / "eval/results"; out_dir.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    tag = args.ids and "ids" or args.only
    csv_path = out_dir / f"run-{stamp}-{tag}.csv"
    fields = ["id", "type", "user", "retrieval_hit", "leakage", "correct", "latency_s", "cited", "error", "answer"]
    rows = []

    print(f"{len(cases)} cases ({tag}) · judge={JUDGE_MODEL} · timeout={int(TIMEOUT_S)}s -> {csv_path.name}")
    with httpx.Client(timeout=TIMEOUT_S) as client:
        for case in cases:
            t0 = time.perf_counter()
            try:
                row = run_case(client, case)
            except Exception as e:                      # one bad case must not lose the run
                row = {"id": case["id"], "type": case.get("type", "qa"), "user": case["user"],
                       "retrieval_hit": None, "leakage": None, "correct": False,
                       "latency_s": round(time.perf_counter() - t0, 1), "cited": "",
                       "error": f"{type(e).__name__}: {e}", "answer": ""}
            rows.append(row)
            with open(csv_path, "w", newline="", encoding="utf-8") as f:   # rewrite after every case
                w = csv.DictWriter(f, fieldnames=fields); w.writeheader(); w.writerows(rows)
            flag = f"ERROR {row['error'][:60]}" if row["error"] else \
                   f"leak={str(row['leakage']):5} hit={str(row['retrieval_hit']):5} correct={str(row['correct']):5}"
            print(f"{row['id']:6} {flag} {row['latency_s']}s")

    qa = [r for r in rows if r["type"] == "qa" and not r["error"]]
    lk = [r for r in rows if r["type"] == "leakage" and not r["error"]]
    rf = [r for r in rows if r["type"] == "refusal" and not r["error"]]
    done = [r for r in rows if not r["error"]]
    # A machine that sleeps mid-run leaves a case with an hours-long "latency"; the socket stays open, so no
    # timeout fires. Outliers are excluded from the latency figures and reported separately.
    OUTLIER_S = 600
    lat = sorted(r["latency_s"] for r in done if r["latency_s"] < OUTLIER_S)
    summary = {
        "selection": tag,
        "judge_model": JUDGE_MODEL,
        "cases": len(rows),
        "errors": sum(1 for r in rows if r["error"]),
        "qa_correctness": round(sum(r["correct"] for r in qa) / len(qa), 3) if qa else None,
        "qa_retrieval_hit_rate": round(sum(bool(r["retrieval_hit"]) for r in qa) / len(qa), 3) if qa else None,
        "leakage_cases": sum(1 for r in rows if r["leakage"]),
        "leakage_refusals_correct": round(sum(r["correct"] for r in lk) / len(lk), 3) if lk else None,
        "out_of_scope_refusals_correct": round(sum(r["correct"] for r in rf) / len(rf), 3) if rf else None,
        "median_latency_s": lat[len(lat) // 2] if lat else None,
        "max_latency_s": max(lat, default=None),
        "latency_outliers_over_600s": sum(1 for r in done if r["latency_s"] >= OUTLIER_S),
    }
    (out_dir / f"summary-{stamp}-{tag}.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))
    sys.exit(1 if summary["leakage_cases"] else 0)      # leakage fails the build; errors do not


if __name__ == "__main__":
    main()
