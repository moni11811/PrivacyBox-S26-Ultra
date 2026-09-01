#!/usr/bin/env python3
"""Reject direct workflow inputs embedded in GitHub Actions shell source."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github" / "workflows"
LOCAL_ACTIONS = ROOT / ".github" / "actions"
RUN_KEY = re.compile(r"^(?P<indent> *)(?:- +)?(?:run|['\"]run['\"]) *: *(?P<value>.*)$")
BLOCK_HEADER = re.compile(r"^[>|](?:[1-9][+-]?|[+-][1-9]?)?(?:\s+#.*)?$", re.ASCII)
DIRECT_INPUT = re.compile(
  r"\$\{\{(?:(?!\}\})[\s\S])*?(?:\binputs\b|['\"]inputs['\"])",
  re.IGNORECASE,
)
DANGEROUS_SHELL_EVAL = re.compile(r"(?:^|[;&|]\s*|\s)(?:eval|(?:ba|z|k)?sh\s+-c)\b")
RELEASE_WORKFLOW = Path(".github/workflows/release-candidate.yml")
ALLOWED_INPUT_BINDING = "SOURCE_TAG: ${{ inputs.source_tag }}"


@dataclass(frozen=True)
class RunScalar:
  start_line: int
  lines: tuple[tuple[int, str], ...]


def leading_spaces(line: str) -> int:
  return len(line) - len(line.lstrip(" "))


def run_scalars(text: str) -> list[RunScalar]:
  """Extract run scalar values while skipping nested text inside block scalars."""
  lines = text.splitlines()
  scalars: list[RunScalar] = []
  index = 0
  while index < len(lines):
    match = RUN_KEY.match(lines[index])
    if match is None:
      index += 1
      continue

    value = match.group("value").strip()
    start_line = index + 1
    if BLOCK_HEADER.fullmatch(value):
      base_indent = len(match.group("indent"))
      collected: list[tuple[int, str]] = []
      index += 1
      while index < len(lines):
        line = lines[index]
        if line.strip() and leading_spaces(line) <= base_indent:
          break
        collected.append((index + 1, line))
        index += 1
      scalars.append(RunScalar(start_line, tuple(collected)))
      continue

    scalars.append(RunScalar(start_line, ((start_line, value),)))
    index += 1
  return scalars


def violations(path: Path) -> list[tuple[int, str]]:
  found: list[tuple[int, str]] = []
  for scalar in run_scalars(path.read_text(encoding="utf-8")):
    if not scalar.lines:
      continue
    value = "\n".join(line for _, line in scalar.lines)
    first_line = scalar.lines[0][0]
    for match in DIRECT_INPUT.finditer(value):
      line_number = first_line + value[:match.start()].count("\n")
      line = value.splitlines()[line_number - first_line].strip()
      found.append((line_number, line))
  return found


def input_references(text: str) -> list[tuple[int, str]]:
  lines = text.splitlines()
  found: list[tuple[int, str]] = []
  for match in DIRECT_INPUT.finditer(text):
    line_number = text[:match.start()].count("\n") + 1
    found.append((line_number, lines[line_number - 1].strip()))
  return found


def dangerous_shell_evaluations(text: str) -> list[tuple[int, str]]:
  found: list[tuple[int, str]] = []
  for scalar in run_scalars(text):
    for line_number, line in scalar.lines:
      if DANGEROUS_SHELL_EVAL.search(line):
        found.append((line_number, line.strip()))
  return found


def main() -> int:
  workflow_files = sorted((*WORKFLOWS.glob("*.yml"), *WORKFLOWS.glob("*.yaml")))
  if not workflow_files:
    print("No workflow YAML files found.", file=sys.stderr)
    return 1
  action_files = sorted((*LOCAL_ACTIONS.rglob("action.yml"), *LOCAL_ACTIONS.rglob("action.yaml")))
  yaml_files = [*workflow_files, *action_files]

  run_block_count = 0
  failures: list[str] = []
  for path in yaml_files:
    text = path.read_text(encoding="utf-8")
    run_block_count += len(run_scalars(text))
    for line_number, line in violations(path):
      failures.append(f"{path.relative_to(ROOT)}:{line_number}: direct workflow input in run scalar: {line}")
    references = input_references(text)
    relative = path.relative_to(ROOT)
    if relative == RELEASE_WORKFLOW:
      if len(references) != 1 or references[0][1] != ALLOWED_INPUT_BINDING:
        failures.append(
          f"{relative}: raw workflow input must appear exactly once as the SOURCE_TAG env binding"
        )
    elif references:
      failures.append(f"{relative}: raw workflow inputs are not allowlisted in this workflow")
    for line_number, line in dangerous_shell_evaluations(text):
      failures.append(f"{relative}:{line_number}: dynamic shell evaluation is forbidden: {line}")

  if failures:
    print("\n".join(failures), file=sys.stderr)
    return 1
  print(
    f"Verified {run_block_count} workflow/composite-action run scalars and the single raw-input binding: "
    "no shell input interpolation or dynamic evaluation."
  )
  return 0


if __name__ == "__main__":
  raise SystemExit(main())
