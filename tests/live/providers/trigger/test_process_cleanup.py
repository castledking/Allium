import subprocess
import sys
import unittest
from process_cleanup import stop_process


class ProcessCleanupTest(unittest.TestCase):
    def child(self, code):
        proc = subprocess.Popen([sys.executable, "-u", "-c", code], stdin=subprocess.PIPE,
                                stdout=subprocess.PIPE, text=True)
        self.addCleanup(self.reap, proc)
        return proc

    @staticmethod
    def reap(proc):
        if proc.poll() is None:
            proc.kill()
        proc.wait(timeout=3)
        if proc.stdin and not proc.stdin.closed:
            proc.stdin.close()
        if proc.stdout:
            proc.stdout.close()

    def test_clean_stdin_shutdown_passes(self):
        proc = self.child("import sys; sys.stdin.readline()")
        stop_process(proc, lambda: (proc.stdin.write("stop\n"), proc.stdin.flush()), timeout=2)
        self.assertEqual(proc.returncode, 0)

    def test_already_exited_nonzero_fails(self):
        proc = self.child("raise SystemExit(7)")
        proc.wait(timeout=2)
        with self.assertRaisesRegex(RuntimeError, "nonzero: 7"):
            stop_process(proc, lambda: None)

    def test_nonzero_after_shutdown_request_fails(self):
        proc = self.child("import sys; sys.stdin.readline(); raise SystemExit(9)")
        with self.assertRaisesRegex(RuntimeError, "nonzero: 9"):
            stop_process(proc, lambda: (proc.stdin.write("stop\n"), proc.stdin.flush()), timeout=2)

    def test_forced_zero_exit_still_fails(self):
        proc = self.child("import signal,time; signal.signal(signal.SIGTERM,lambda *a:exit(0)); print('ready'); time.sleep(30)")
        self.assertEqual(proc.stdout.readline().strip(), "ready")
        with self.assertRaisesRegex(RuntimeError, "forced shutdown"):
            stop_process(proc, lambda: None, timeout=0.05, terminate_timeout=2)
        self.assertEqual(proc.returncode, 0)

    def test_ignored_term_is_killed_reaped_and_fails(self):
        proc = self.child("import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); print('ready'); time.sleep(30)")
        self.assertEqual(proc.stdout.readline().strip(), "ready")
        with self.assertRaisesRegex(RuntimeError, "forced shutdown"):
            stop_process(proc, lambda: None, timeout=0.05, terminate_timeout=0.1)
        self.assertIsNotNone(proc.returncode)


if __name__ == "__main__":
    unittest.main()
