package adapters

import "strings"

// stringReader is a tiny helper used by decode-side tests. Keeps them
// off strings.NewReader in the test body so the assertions stay flat.
func stringReader(s string) *strings.Reader { return strings.NewReader(s) }
