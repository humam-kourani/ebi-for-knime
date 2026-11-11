# Synthetic regression fixtures

These fixtures were created for the extension tests and contain no real personal/event data.

- `constant.xes`: four identical A-then-B traces. Trace attributes include categorical `segment`, numeric `amount`, and timestamps. Useful for exact identity conformance, completeness1, variety0, and deterministic models.
- `tiny-attributed.xes`: six traces: AB, AB, AC, AC, A, empty. Includes case IDs1-6, standard/premium groups, integer amounts10-60, and ordered event timestamps. Exact expected filtering preserves five traces after empty removal, four after removing any C trace, and four after removing lengths below2.
- `tiny-unordered.xes`: same six traces, with descending event timestamps in the first trace. The ordering predicate must be false.
- `single-a.xes`: one A trace.
- `weighted.slpn`: one XOR choice from initial to final place, A weight3 and B weight1. Native probability of the single-A language must be exactly3/4. Raw SLPN conversion must preserve both weights.
- `upstream-bugs/`: actual captured outputs of upstream native binaries on synthetic fixtures. These deliberately include malformed or misleading results so the Java adapters can be checked against real failures. See `docs/upstream-issues.md`.

`tests/run_command_tests.py` derives fresh PNML/PTML/DFG model fixtures from the actual Ebi library at runtime; UUIDs, randomized discovery, and sampled association outputs are checked using structural/exact invariants rather than byte snapshots. The default native suite accepts known upstream findings as separately reported statuses. Use `--fail-on-known` to make those findings a failing gate.
