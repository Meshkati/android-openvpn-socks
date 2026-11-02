package engine

import (
	"os"
	"runtime/debug"
)

// SetCrashFile makes the Go runtime also write a fatal panic's message and
// goroutine stacks to path, as well as to stderr (which Android drops). The
// app reads the file on its next start and offers it as a crash report;
// nothing is sent anywhere from here. New output is appended.
func SetCrashFile(path string) error {
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_APPEND, 0o600)
	if err != nil {
		return err
	}
	// SetCrashOutput keeps its own duplicate of the descriptor.
	defer f.Close()
	return debug.SetCrashOutput(f, debug.CrashOptions{})
}
