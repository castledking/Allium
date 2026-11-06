"""Owned subprocess cleanup that cannot turn infrastructure failures into passes."""
import subprocess


def stop_process(proc, request_stop, timeout=45, terminate_timeout=12):
    if proc is None:
        return
    forced = False
    try:
        if proc.poll() is None:
            try:
                request_stop()
            except Exception:
                pass
            try:
                proc.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                forced = True
                proc.terminate()
                try:
                    proc.wait(timeout=terminate_timeout)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait(timeout=terminate_timeout)
        if forced:
            raise RuntimeError("process required forced shutdown; exit=" + str(proc.returncode))
        if proc.returncode != 0:
            raise RuntimeError("process exited nonzero: " + str(proc.returncode))
    finally:
        if proc.stdin:
            try:
                proc.stdin.close()
            except OSError:
                pass
