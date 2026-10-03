import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]


class NativeRendererContractTests(unittest.TestCase):
    def test_graphics_hook_has_one_pass_through_owner(self):
        analysis = (ROOT / "config/rexglue/analysis/main-xex.toml").read_text(
            encoding="utf-8"
        )
        self.assertEqual(analysis.count('name = "PinyonShiftObserveGraphicsFrame"'), 1)
        hook = analysis.split('name = "PinyonShiftObserveGraphicsFrame"', 1)[0]
        hook = hook.rsplit("[[midasm_hook]]", 1)[1]
        self.assertIn("address = 0x829EFEB8", hook)
        self.assertNotIn("jump_address", hook)
        self.assertNotIn("after_instruction", hook)
        self.assertNotIn("registers", hook)

    def test_dependency_ledger_keeps_gate_b_closed(self):
        ledger = (
            ROOT / "docs/native-renderer/GUEST_VISIBLE_RENDER_DEPENDENCIES.md"
        ).read_text(encoding="utf-8")
        self.assertIn("No render target is suppression-eligible yet.", ledger)
        self.assertIn("Gate B remains closed", ledger)
        self.assertIn("unknown_uninstrumented", ledger)
        self.assertIn("unknown_unclassified", ledger)

    def test_census_has_no_native_renderer_or_suppression_api(self):
        source = (ROOT / "src/native_renderer/graphics_hooks.cpp").read_text(
            encoding="utf-8"
        )
        forbidden = (
            "native_rhi",
            "NativeRHI",
            "IssueDraw",
            "IssueCopy",
            "SetDrawSuppression",
            "SetCopySuppression",
        )
        for token in forbidden:
            self.assertNotIn(token, source)

        cmake = (ROOT / "CMakeLists.txt").read_text(encoding="utf-8")
        # Once: the guest_output_renderer host tool was retired with the GPU
        # corpus (2550904), only the hooked renderer build references it now.
        self.assertEqual(cmake.count("src/native_renderer/graphics_hooks.cpp"), 1)

    def test_census_ledger_tracks_exact_starting_baseline(self):
        ledger = (ROOT / "docs/native-renderer/archive/RENDER_PASS_CENSUS.md").read_text(
            encoding="utf-8"
        )
        self.assertIn("cafc7233fef9e039f163d11023f40eccb22e8fc1", ledger)
        self.assertIn("f5337cdc947ff6d4c4196737e2c807a48f2a1fc2", ledger)
        self.assertIn("Unknown work stays on Xenos.", ledger)


if __name__ == "__main__":
    unittest.main()
