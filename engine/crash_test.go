package engine

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// A fatal panic after SetCrashFile leaves its message and stack in the file.
// The panic runs in a child process (this test binary, re-run).
func TestCrashFileGetsPanic(t *testing.T) {
	if path := os.Getenv("ENGINE_CRASH_FILE"); path != "" {
		if err := SetCrashFile(path); err != nil {
			t.Fatal(err)
		}
		panic("engine test panic")
	}
	path := filepath.Join(t.TempDir(), "go.txt")
	cmd := exec.Command(os.Args[0], "-test.run=^TestCrashFileGetsPanic$")
	cmd.Env = append(os.Environ(), "ENGINE_CRASH_FILE="+path)
	if err := cmd.Run(); err == nil {
		t.Fatal("child did not crash")
	}
	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"panic: engine test panic", "TestCrashFileGetsPanic"} {
		if !strings.Contains(string(got), want) {
			t.Errorf("crash file lacks %q:\n%s", want, got)
		}
	}
}

func TestCrashFileBadPath(t *testing.T) {
	if err := SetCrashFile(filepath.Join(t.TempDir(), "missing", "go.txt")); err == nil {
		t.Fatal("no error for a directory that does not exist")
	}
}
