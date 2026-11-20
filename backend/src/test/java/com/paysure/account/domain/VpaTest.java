package com.paysure.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paysure.common.error.BadRequestException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class VpaTest {

    @Test
    void normalisesHandleToLowerCase() {
        assertThat(Vpa.fromHandle("  Ananya.S ", "paysure").value()).isEqualTo("ananya.s@paysure");
    }

    @Test
    void parsesFullVpa() {
        assertThat(Vpa.parse(" RAHUL@PaySure ").value()).isEqualTo("rahul@paysure");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ab", "_lead", ".lead", "has space", "emoji😀x", "toolong-abcdefghijklmnopqrstuvwxyz", "semi;colon"})
    void rejectsInvalidHandles(String handle) {
        assertThatThrownBy(() -> Vpa.fromHandle(handle, "paysure")).isInstanceOf(BadRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "Treasury", "NPCI", "paysure"})
    void rejectsReservedHandles(String handle) {
        assertThatThrownBy(() -> Vpa.fromHandle(handle, "paysure"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("reserved");
    }

    @ParameterizedTest
    @ValueSource(strings = {"rahul", "rahul@", "@paysure", "rahul@pay sure", "rahul@paysure@x", "rahul@ok1"})
    void rejectsInvalidFullVpas(String raw) {
        assertThatThrownBy(() -> Vpa.parse(raw)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> Vpa.parse(null)).isInstanceOf(BadRequestException.class);
    }
}
