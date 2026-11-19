import tempfile
import unittest
from pathlib import Path

from measure import NS, complete_frames_peak, cpu_phase, frame_stats, one_second_peak, read_events


class MeasureTest(unittest.TestCase):
    def test_probe_events_require_complete_order_and_twenty_seconds_of_warmup(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)/'events.tsv'
            def parse(events):
                p.write_text(''.join(f'{name}\t{ns}\t{ns//1_000_000}\n' for name,ns in events))
                return read_events(p)
            events = [('TITLE_READY',0),('WARMUP_BEGIN',NS),('SAMPLE_BEGIN',21*NS),('SAMPLE_END',61*NS),('DONE',62*NS)]
            self.assertEqual(parse(events)['SAMPLE_BEGIN'][0],21*NS)
            for bad in (events[:1]+events[2:],events[:2]+events[1:],events[:1]+events[2:3]+events[1:2]+events[3:],
                        events[:2]+[('SAMPLE_BEGIN',21*NS-1)]+events[3:]):
                with self.subTest(bad=bad), self.assertRaises(ValueError):
                    parse(bad)

    def test_cpu_interpolates_boundaries_and_normalizes_against_host(self):
        result = cpu_phase([(0, 0), (NS, 2_000_000), (2*NS, 2_000_000)], NS//2, 2*NS, 8)
        self.assertEqual(result['cpu_s'], 1)
        self.assertAlmostEqual(result['mean_cpu_pct'], 100/1.5)
        self.assertAlmostEqual(result['mean_machine_pct'], 100/1.5/8)
        self.assertEqual(result['peak_1s_cpu_pct'], 100)
        self.assertEqual(result['peak_sample_cpu_pct'], 200)

    def test_sustained_peak_excludes_short_spike(self):
        self.assertEqual(one_second_peak([0, NS, NS+NS//4, 2*NS], [0, 5, 30, 36]), 31)
        self.assertIsNone(one_second_peak([0, NS//2], [0, 60]))

    def test_raw_frame_sequence_and_nearest_rank(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)/'frames.csv'
            dt = [10_000_000]*18 + [20_000_000, 100_000_000]
            end = 0
            rows = ['frame,end_ns,dt_ns']
            for i, duration in enumerate(dt, 1):
                end += duration
                rows.append(f'{i},{end},{duration}')
            p.write_text('\n'.join(rows))
            result = frame_stats(p, 0, end)
            self.assertAlmostEqual(result['fps'], 20/.3)
            self.assertEqual(result['low95_fps'], 50)
            self.assertEqual(result['peak_single_frame_fps'], 100)
            p.write_text('\n'.join(rows[:-2] + rows[-1:]))
            with self.assertRaisesRegex(ValueError, '不连续'):
                frame_stats(p, 0, end)

    def test_one_second_window_counts_only_complete_intervals(self):
        self.assertEqual(complete_frames_peak([0, 600_000_000, 1_200_000_000]), 1)
        self.assertEqual(complete_frames_peak([0, NS, NS+500_000_000, 2*NS]), 2)
        self.assertEqual(complete_frames_peak([0, 2*NS]), 0)
        self.assertIsNone(complete_frames_peak([0, NS-1]))
