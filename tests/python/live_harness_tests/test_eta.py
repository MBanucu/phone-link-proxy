"""The ETA arithmetic, ported branch for branch from ``tests/eta.test.sh``."""

from __future__ import annotations

import unittest

from . import fixtures, support
from harness import eta


class EtaTest(support.Case):
    def write_timings(self, text: str, name: str = "timings.csv"):
        return self.sandbox.write(name, text)

    def test_medians_and_runs_are_not_steps(self) -> None:
        path = self.write_timings(fixtures.ETA_TIMINGS)
        medians = eta.load(path)
        self.same(medians.get("panel"), 12000, "a step is the median of its runs")
        self.same(medians.get("rename"), 6000, "a second step too")
        self.same("TOTAL" in medians, False, "the run total is not a step")
        self.same("PARTIAL" in medians, False, "neither is a partial run")
        self.same(
            eta.fallback(medians), 9000, "a step with no history borrows the median"
        )

    def test_last_runs_window(self) -> None:
        path = self.write_timings(fixtures.ETA_TIMINGS)
        medians = eta.load(path, 2)
        self.same(medians.get("panel"), 13000, "only the last runs count")
        self.same(medians.get("rename"), 7000, "an even count averages the middle two")

    def test_fmt(self) -> None:
        self.same(eta.format_ms(400), "0.4s", "a fraction of a second")
        self.same(eta.format_ms(12400), "12.4s", "a short step")
        self.same(eta.format_ms(62000), "1m02s", "minutes")
        self.same(eta.format_ms(3730000), "1h02m", "an hour")
        self.same(eta.format_ms(-5), "0.0s", "nothing left is not negative")

    def test_left(self) -> None:
        self.same(eta.left(10000, 20000, 4000), 26000, "the step still has some")
        self.same(eta.left(10000, 20000, 10000), 20000, "exactly on its estimate")
        self.same(eta.left(10000, 20000, 20000), 40000, "twice the estimate slows all")
        self.same(eta.left(0, 20000, 99000), 20000, "no estimate cannot slow")

    def test_plan_with_history_and_a_guess(self) -> None:
        path = self.write_timings(fixtures.ETA_TIMINGS)
        plan = eta.plan(
            ["panel", "rename", "never-seen"], medians=eta.load(path)
        )
        self.same(plan.total_ms, 27000, "the plan adds the steps it can price")
        self.true(plan.lines[0].startswith("plan 27.0s: "), "the total is first")
        self.contains(
            plan.lines[0],
            "~9.0s never-seen",
            "a step with no history is priced and marked as a guess",
        )
        self.contains(
            plan.lines[1],
            "never-seen",
            "the borrowed step is named after the plan",
        )
        self.same(
            plan.text.splitlines()[0],
            "plan 27.0s: 12.0s panel · 6.0s rename · ~9.0s never-seen ·",
            "the plan line is the shell's shape",
        )

    def test_empty_history_invents_nothing(self) -> None:
        path = self.write_timings(
            "run,step,seconds,passed,failed,status\n", "empty.csv"
        )
        medians = eta.load(path)
        self.same(len(medians), 0, "an empty history prices nothing")
        plan = eta.plan(["panel"], medians=medians)
        self.same(plan.total_ms, 0, "and claims no time")
        self.contains(plan.lines[0], "? panel", "the step is a question mark")

    def test_caller_prices_are_the_plan(self) -> None:
        plan = eta.plan(
            ["panel", "rename"],
            estimate={"panel": 5000, "rename": 9000},
            borrowed=" rename",
        )
        self.same(plan.total_ms, 14000, "the caller's prices are the plan")
        self.same(
            plan.lines[0],
            "plan 14.0s: 5.0s panel · ~9.0s rename ·",
            "only the borrowed one is marked",
        )

    def test_prices_marks_steps_without_history(self) -> None:
        estimate, borrowed = eta.prices(
            ["panel", "rename", "new"], {"panel": 5000}, 9000
        )
        self.same(estimate, {"panel": 5000, "rename": 9000, "new": 9000}, "prices")
        self.same(borrowed, ["rename", "new"], "the ones the caller borrowed")

    def test_mark_fields(self) -> None:
        order = ["panel", "rename", "never-seen"]
        estimate = {"panel": 10000, "rename": 6000, "never-seen": 9000}
        line = eta.mark_fields(order, estimate, "rename", 5000)
        self.same(
            line,
            "rename\t6000\t9000\t5000\t1\n",
            "the mark names the step, its price, what follows and when",
        )
        self.same(
            eta.mark_fields(order, estimate, "unknown", 1),
            None,
            "a step outside the order has no mark",
        )

    def test_write_mark_is_atomic_and_readable(self) -> None:
        path = self.sandbox.root / "state"
        self.true(
            eta.write_mark(
                path,
                ["panel", "rename"],
                {"panel": 10000, "rename": 6000},
                "rename",
                5000,
            ),
            "the mark is written",
        )
        self.same(
            eta.read_mark(path),
            ("rename", 6000, 0, 5000, 0),
            "and reads back as five fields",
        )
        self.false(
            eta.write_mark("", ["panel"], {"panel": 1}, "panel", 0),
            "no state path means no ticker",
        )

    def test_ticker_first_line(self) -> None:
        path = self.sandbox.root / "state"
        eta.write_mark(
            path,
            ["panel", "rename", "never-seen"],
            {"panel": 10000, "rename": 6000, "never-seen": 9000},
            "rename",
            5000,
        )
        ticker = eta.Ticker(path, now_ms=lambda: 7000)
        lines = ticker.tick()
        self.same(len(lines), 1, "one line for a step inside its estimate")
        self.matches(
            lines[0],
            r"^eta .+ left  \(rename .+ in, usually 6\.0s; 1 step after\)$",
            "the ticker says what is left and where the run is",
        )
        self.contains(lines[0], "1 step after", "and counts the steps after it")

    def test_ticker_says_over_its_estimate(self) -> None:
        path = self.sandbox.root / "state"
        eta.write_mark(path, ["panel"], {"panel": 10000}, "panel", 0)
        ticker = eta.Ticker(path, now_ms=lambda: 20000)
        lines = ticker.tick()
        self.contains(
            lines[-1],
            "over its estimate",
            "a step past its estimate says so instead of claiming none is left",
        )

    def test_mixed_history_and_full_mode(self) -> None:
        path = self.write_timings(fixtures.ETA_MIXED, "mixed.csv")
        self.same(
            eta.load(path).get("panel"),
            13000,
            "a file of both kinds mixes them by default",
        )
        self.same(
            eta.load(path, 5, full=True).get("panel"),
            12000,
            "asked for the full runs, the filtered one does not count",
        )
        self.same(
            eta.load(path, 5, full=True).get("rename"),
            6000,
            "and neither do its other steps",
        )

    def test_slow_thresholds(self) -> None:
        self.true(eta.slow(30000, 60000), "twice and half a minute over is news")
        self.false(eta.slow(30000, 59999), "a second short is not")
        self.false(eta.slow(2000, 5000), "a small step at twice its time is not")
        self.false(eta.slow(0, 999999), "a step nothing priced cannot be late")
        self.true(
            eta.slow(2000, 5000, slow_ms=500),
            "how far over counts is a setting",
        )

    def test_slow_is_said_once_per_step(self) -> None:
        path = self.sandbox.root / "state"
        now = 10000
        eta.write_mark(path, ["panel", "rename"], {"panel": 2000, "rename": 6000}, "panel", 6000)
        ticker = eta.Ticker(path, now_ms=lambda: now, slow_ms=500)
        first = ticker.tick()
        second = ticker.tick()
        self.same(
            sum(1 for line in first + second if line.startswith("slow panel")),
            1,
            "a step that ran away is said once, not on every tick",
        )
        warning = next(line for line in first if line.startswith("slow panel"))
        self.matches(
            warning,
            r"^slow panel [0-9.]+s in, usually 2\.0s \([0-9.]+×, [0-9.]+s over\)$",
            "and says how far past its estimate it is, in time and ratio",
        )

    def test_ticker_resets_the_warning_on_a_new_step(self) -> None:
        path = self.sandbox.root / "state"
        eta.write_mark(path, ["panel", "rename"], {"panel": 2000, "rename": 2000}, "panel", 0)
        ticker = eta.Ticker(path, now_ms=lambda: 10000, slow_ms=500)
        self.same(
            sum(1 for line in ticker.tick() if line.startswith("slow")),
            1,
            "the first step warns",
        )
        eta.write_mark(path, ["panel", "rename"], {"panel": 2000, "rename": 2000}, "rename", 0)
        self.same(
            sum(1 for line in ticker.tick() if line.startswith("slow rename")),
            1,
            "a new step gets its own warning",
        )

    def test_results_fixture_is_read(self) -> None:
        path = self.write_timings(fixtures.RESULTS_TIMINGS, "results.csv")
        medians = eta.load(path)
        self.same(medians.get("unlock"), 3000, "the results fixture's unlock")
        self.same(medians.get("panel"), 12500, "and its panel")
        self.same("TOTAL" in medians, False, "its total is not a step")


if __name__ == "__main__":
    unittest.main()
