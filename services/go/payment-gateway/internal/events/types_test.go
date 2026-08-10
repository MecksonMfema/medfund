package events

import "testing"

func TestParseRunExecuted(t *testing.T) {
	cases := []struct {
		name    string
		body    string
		wantOK  bool
		wantLen int
	}{
		{
			name: "valid full payload",
			body: `{
			  "event":"PAYMENT_RUN_EXECUTED",
			  "runId":"run-1","runNumber":"RUN-001",
			  "tenantId":"tenant-abc","sourceBankAccountId":"bank-1",
			  "currencyCode":"USD","paymentCount":"2",
			  "items":[
			    {"itemId":"item-1","paymentId":"pay-1","providerId":"prov-1","memberId":"","amount":"150.00","currencyCode":"USD"},
			    {"itemId":"item-2","paymentId":"pay-2","providerId":"prov-2","memberId":"","amount":"75.50","currencyCode":"USD"}
			  ]}`,
			wantOK:  true,
			wantLen: 2,
		},
		{
			name:   "missing tenantId",
			body:   `{"runId":"run-1","runNumber":"RUN-001","currencyCode":"USD","items":[]}`,
			wantOK: false,
		},
		{
			name:   "missing runId",
			body:   `{"tenantId":"tenant-1","runNumber":"RUN-001","currencyCode":"USD","items":[]}`,
			wantOK: false,
		},
		{
			name:   "malformed json",
			body:   `{not valid`,
			wantOK: false,
		},
		{
			name:    "empty items ok — no work but not malformed",
			body:    `{"runId":"run-1","runNumber":"RUN-001","tenantId":"t-1","items":[]}`,
			wantOK:  true,
			wantLen: 0,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, ok := ParseRunExecuted([]byte(tc.body))
			if ok != tc.wantOK {
				t.Fatalf("ok = %v, want %v", ok, tc.wantOK)
			}
			if tc.wantOK && len(got.Items) != tc.wantLen {
				t.Fatalf("items = %d, want %d", len(got.Items), tc.wantLen)
			}
		})
	}
}
