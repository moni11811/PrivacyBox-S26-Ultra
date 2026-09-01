#!/usr/bin/env python3

from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
import sys
import unittest


SCRIPT = Path(__file__).with_name("verify_workflow_run_inputs.py")
SPEC = spec_from_file_location("verify_workflow_run_inputs", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class WorkflowRunInputTest(unittest.TestCase):
  def test_environment_mediation_is_allowed(self) -> None:
    text = """\
jobs:
  verify:
    steps:
      - env:
          SOURCE_TAG: ${{ inputs.source_tag }}
        run: |
          TAG="$SOURCE_TAG"
          printf '%s\\n' "$TAG"
"""
    self.assertEqual([], [line for scalar in MODULE.run_scalars(text) for line in scalar.lines if MODULE.DIRECT_INPUT.search(line[1])])

  def test_block_scalar_direct_input_is_rejected(self) -> None:
    text = """\
jobs:
  verify:
    steps:
      - run: |
          TAG="${{ inputs.source_tag }}"
"""
    matches = [line for scalar in MODULE.run_scalars(text) for line in scalar.lines if MODULE.DIRECT_INPUT.search(line[1])]
    self.assertEqual(1, len(matches))
    self.assertEqual(5, matches[0][0])

  def test_folded_and_inline_run_scalars_are_parsed(self) -> None:
    text = """\
steps:
  - run: >-
      echo "${{ format('{0}', inputs.first) }}"
  - run: echo "${{ github['event']['inputs']['second'] }}"
"""
    matches = [line for scalar in MODULE.run_scalars(text) for line in scalar.lines if MODULE.DIRECT_INPUT.search(line[1])]
    self.assertEqual([3, 4], [line[0] for line in matches])

  def test_multiline_nested_input_expression_is_rejected(self) -> None:
    text = """\
steps:
  - run: |
      echo "${{ format(
        '{0}',
        github.event.inputs.source_tag
      ) }}"
"""
    scalar = MODULE.run_scalars(text)[0]
    value = "\n".join(line for _, line in scalar.lines)
    self.assertIsNotNone(MODULE.DIRECT_INPUT.search(value))

  def test_block_scalar_header_comment_is_parsed(self) -> None:
    text = """\
steps:
  - run: | # shell
      echo "${{ inputs.source_tag }}"
"""
    scalar = MODULE.run_scalars(text)[0]
    value = "\n".join(line for _, line in scalar.lines)
    self.assertIsNotNone(MODULE.DIRECT_INPUT.search(value))

  def test_alias_flow_and_escaped_run_keys_cannot_hide_input_references(self) -> None:
    text = r"""\
x-run: &cmd |
  echo "${{ inputs.alias }}"
steps:
  - { run: 'echo "${{ inputs.flow }}"' }
  - "r\u0075n": echo "${{ inputs.escaped }}"
"""
    self.assertEqual(3, len(MODULE.input_references(text)))

  def test_dynamic_shell_evaluation_is_rejected(self) -> None:
    text = """\
steps:
  - run: |
      eval "$SOURCE_TAG"
"""
    self.assertEqual([(3, 'eval "$SOURCE_TAG"')], MODULE.dangerous_shell_evaluations(text))

  def test_run_text_inside_a_run_block_is_not_reparsed_as_yaml(self) -> None:
    text = """\
steps:
  - run: |
      printf 'run: ${{ inputs.nested }}'
  - name: next
    env:
      VALUE: ${{ inputs.allowed }}
"""
    scalars = MODULE.run_scalars(text)
    self.assertEqual(1, len(scalars))
    self.assertEqual(1, len([line for line in scalars[0].lines if MODULE.DIRECT_INPUT.search(line[1])]))


if __name__ == "__main__":
  unittest.main()
