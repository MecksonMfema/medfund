package com.medfund.shared.report.regulatory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LabelAnchorTest {

    @Test
    void construct_holdsThreeAxes() {
        var a = new LabelAnchor("Sheet1", "Total Assets", "Amount");
        assertThat(a.sheet()).isEqualTo("Sheet1");
        assertThat(a.rowLabel()).isEqualTo("Total Assets");
        assertThat(a.columnHeader()).isEqualTo("Amount");
    }

    @Test
    void equality_isValueBased() {
        var a = new LabelAnchor("Sheet1", "Total Assets", "Amount");
        var b = new LabelAnchor("Sheet1", "Total Assets", "Amount");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void reject_blankOrNullSheet() {
        assertThatThrownBy(() -> new LabelAnchor(null, "row", "col"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sheet");
        assertThatThrownBy(() -> new LabelAnchor("  ", "row", "col"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reject_blankOrNullRowLabel() {
        assertThatThrownBy(() -> new LabelAnchor("s", null, "col"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rowLabel");
    }

    @Test
    void reject_blankOrNullColumnHeader() {
        assertThatThrownBy(() -> new LabelAnchor("s", "r", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("columnHeader");
    }
}
