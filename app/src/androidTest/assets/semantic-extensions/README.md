# Semantic Extensions reference data

The two JSON fixtures were captured from the original Extensions 0.13.0 Android
AAR (`cb98c6fbeac1a9707228c4b76cfbb395b4dc4d310900aefaa672561d17fd6d9d`)
with IPA Board commit `a2e3bc84cc9471831e0dd049c570edd8f4d96d1f`.
They contain only fixed synthetic text, not user input. Build provenance and the
original capture reports are maintained in the IPA_Engine `ranking` branch's
`validation` directory.

- `tokenizer-baseline.json`: exact token IDs for 17 multilingual/Unicode samples.
- `e5-baseline.json`: 22 real semantic scores for five fixed context probes.
- `malformed-model.onnx`: deliberately invalid synthetic bytes for model-init
  failure and recovery. It is never installed as a production model.

The test verifies the pinned model/tokenizer identity before using references.
Token comparisons are exact; scores use absolute tolerance `0.0001` across the
supported native targets. References must not be regenerated merely to accept
a failure: investigate model, tokenizer, preprocessing and numerical changes.

The malformed-model test overlays only a tiny manifest and invalid model inside
a private Resources instance and test-owned directory. The production tokenizer
and calibration remain in the application APK; no large assets are duplicated.
The overlay and private directory are removed in `finally`.

The ordinary semantic test records its actual page size without imposing a
4KB requirement. A strict 16KB acceptance run must independently verify that
the recorded process page size is 16384 and that all behavior tests passed.
