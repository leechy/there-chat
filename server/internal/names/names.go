// Package names suggests random film character names for new chat members.
package names

import (
	_ "embed"
	"math/rand/v2"
	"strings"
)

//go:embed names.txt
var raw string

var list = func() []string {
	var out []string
	for _, line := range strings.Split(raw, "\n") {
		if line = strings.TrimSpace(line); line != "" {
			out = append(out, line)
		}
	}
	return out
}()

// Random returns n distinct random names (fewer if the list is shorter).
func Random(n int) []string {
	n = min(n, len(list))
	out := make([]string, 0, n)
	for _, i := range rand.Perm(len(list))[:n] {
		out = append(out, list[i])
	}
	return out
}
