"""A9 evaluation harness — scores kb-copilot against the D8 golden set.

Checks per case:
  retrieval_hit  expected doc appears in citations            (deterministic)
  leakage        forbidden doc cited OR forbidden string shown (deterministic, must be 0)
  correct        LLM judge (chat-quality, a DIFFERENT model than the answerer)
Run: python -m eval.run_eval   (from eval/src, with .env loaded)
"""
import csv, json, os, pathlib, sys, time
import httpx, yaml
from dotenv import load_dotenv

ROOT = pathlib.Path(__file__).resolve().parents[3]
load_dotenv(ROOT / ".env")

RAG_URL = os.environ["EVAL_RAG_URL"]
RAG_KEY = os.environ["RAG_SERVICE_KEY"]
GW_URL = os.environ["EVAL_GATEWAY_URL"]
GW_KEY = os.environ["LITELLM_MASTER_KEY"]
USER_HEADER = "X-OpenWebUI-User-Email"
JUDGE_MODEL = os.environ["EVAL_JUDGE_MODEL"]

JUDGE_PROMPT = """/no_think
You grade an assistant answer against a reference.
Question: {q}
Reference answer: {ref}
Assistant answer: {ans}
Reply with JSON only: {{"correct": true|false, "reason": "<short>"}}.
"correct" is true if the assistant conveys the reference facts without contradicting them."""


def ask(client, case):
    t0 = time.perf_counter()
    r = client.post(RAG_URL, headers={"Authorization": f"Bearer {RAG_KEY}", USER_HEADER: case["user"]},
                    json={"model": "kb-copilot", "stream": False,
                          "messages": [{"role": "user", "content": case["question"]}]})
    r.raise_for_status()
    body = r.json()
    return body["choices"][0]["message"]["content"], body.get("citations", []), time.perf_counter() - t0


def judge(client, case, answer):
    r = client.post(GW_URL, headers={"Authorization": f"Bearer {GW_KEY}"},
                    json={"model": JUDGE_MODEL, "temperature": 0, "messages": [{"role": "user",
                          "content": JUDGE_PROMPT.format(q=case["question"], ref=case["expected_answer"], ans=answer)}]})
    r.raise_for_status()
    text = r.json()["choices"][0]["message"]["content"]
    text = text[text.find("{"): text.rfind("}") + 1]           # tolerate <think> blocks / fences
    try:
        return bool(json.loads(text)["correct"])
    except Exception:
        return False                                            # unparseable judge = fail, never pass


def main():
    cases = yaml.safe_load((ROOT / "data/golden/golden-set.yaml").read_text())["cases"]
    out_dir = ROOT / "eval/results"; out_dir.mkdir(exist_ok=True)
    rows = []
    with httpx.Client(timeout=900) as client:
        for c in cases:
            answer, cites, secs = ask(client, c)
            cited = {x["doc_id"] for x in cites}
            leak = bool(cited & set(c.get("forbidden_docs", []))) or any(
                s.lower() in answer.lower() for s in c.get("forbidden_strings", []))
            hit = set(c.get("expected_docs", [])) <= cited if c.get("expected_docs") else None
            rows.append({"id": c["id"], "type": c.get("type", "qa"), "user": c["user"],
                         "retrieval_hit": hit, "leakage": leak, "correct": judge(client, c, answer),
                         "latency_s": round(secs, 2), "cited": ";".join(sorted(cited)), "answer": answer})
            print(f'{c["id"]:6} leak={leak!s:5} hit={hit!s:5} correct={rows[-1]["correct"]!s:5} {secs:.1f}s')

    stamp = time.strftime("%Y%m%d-%H%M%S")
    with open(out_dir / f"run-{stamp}.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=rows[0].keys()); w.writeheader(); w.writerows(rows)
    qa = [r for r in rows if r["type"] == "qa"]
    summary = {"cases": len(rows),
               "correctness": round(sum(r["correct"] for r in qa) / max(len(qa), 1), 3),
               "retrieval_hit_rate": round(sum(bool(r["retrieval_hit"]) for r in qa) / max(len(qa), 1), 3),
               "leakage_cases": sum(r["leakage"] for r in rows),
               "p95_latency_s": sorted(r["latency_s"] for r in rows)[int(0.95 * (len(rows) - 1))]}
    (out_dir / f"summary-{stamp}.json").write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary, indent=2))
    sys.exit(1 if summary["leakage_cases"] else 0)            # leakage fails the build


if __name__ == "__main__":
    main()
