/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.libs.mcstructs.dialog.Dialog;
import com.viaversion.viaversion.libs.mcstructs.dialog.impl.MultiActionDialog;
import com.viaversion.viaversion.libs.mcstructs.dialog.impl.NoticeDialog;
import com.viaversion.viaversion.libs.mcstructs.dialog.input.SingleOptionInput;
import net.lenni0451.mcstructs_bedrock.forms.Form;
import net.lenni0451.mcstructs_bedrock.forms.elements.ButtonFormElement;
import net.lenni0451.mcstructs_bedrock.forms.elements.DividerFormElement;
import net.lenni0451.mcstructs_bedrock.forms.elements.DropdownFormElement;
import net.lenni0451.mcstructs_bedrock.forms.elements.StepSliderFormElement;
import net.lenni0451.mcstructs_bedrock.forms.types.ActionForm;
import net.lenni0451.mcstructs_bedrock.forms.types.CustomForm;
import net.lenni0451.mcstructs_bedrock.forms.types.ModalForm;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockFormDialogConverterTest {

    @Test
    void emptyActionFormUsesNoticeDialog() {
        final Dialog dialog = convert(new ActionForm("Title", "Body"));

        assertInstanceOf(NoticeDialog.class, dialog);
        assertDoesNotThrow(() -> BedrockFormDialogConverter.serialize(dialog));
    }

    @Test
    void dividerOnlyActionFormUsesNoticeDialog() {
        final Dialog dialog = convert(new ActionForm("", "", new DividerFormElement()));

        assertInstanceOf(NoticeDialog.class, dialog);
        assertDoesNotThrow(() -> BedrockFormDialogConverter.serialize(dialog));
    }

    @Test
    void emptyDropdownKeepsBedrockDefaultResponseIndex() {
        final MultiActionDialog dialog = assertInstanceOf(MultiActionDialog.class,
                convert(new CustomForm("Title", new DropdownFormElement("Choose"))));
        final SingleOptionInput input = assertInstanceOf(SingleOptionInput.class, dialog.getInputs().getFirst().getControl());

        assertEquals(1, input.getOptions().size());
        assertEquals("0", input.getOptions().getFirst().getId());
        assertTrue(input.getOptions().getFirst().isInitial());
        assertDoesNotThrow(() -> BedrockFormDialogConverter.serialize(dialog));
    }

    @Test
    void emptyStepSliderKeepsBedrockDefaultResponseIndex() {
        final MultiActionDialog dialog = assertInstanceOf(MultiActionDialog.class,
                convert(new CustomForm("Title", new StepSliderFormElement("Choose"))));
        final SingleOptionInput input = assertInstanceOf(SingleOptionInput.class, dialog.getInputs().getFirst().getControl());

        assertEquals(1, input.getOptions().size());
        assertEquals("0", input.getOptions().getFirst().getId());
        assertTrue(input.getOptions().getFirst().isInitial());
        assertDoesNotThrow(() -> BedrockFormDialogConverter.serialize(dialog));
    }

    @Test
    void normalFormsRemainSerializable() {
        final Dialog modal = convert(new ModalForm("Title", "Body", "Yes", "No"));
        final Dialog action = convert(new ActionForm("Title", "Body", new ButtonFormElement("Go")));
        final Dialog custom = convert(new CustomForm("Title", new DropdownFormElement("Choose", 1, "A", "B")));

        assertEquals(2, assertInstanceOf(MultiActionDialog.class, modal).getActions().size());
        assertEquals(1, assertInstanceOf(MultiActionDialog.class, action).getActions().size());
        assertEquals(2, assertInstanceOf(SingleOptionInput.class,
                assertInstanceOf(MultiActionDialog.class, custom).getInputs().getFirst().getControl()).getOptions().size());
        assertDoesNotThrow(() -> BedrockFormDialogConverter.serialize(modal));
        assertDoesNotThrow(() -> BedrockFormDialogConverter.serialize(action));
        assertDoesNotThrow(() -> BedrockFormDialogConverter.serialize(custom));
    }

    @Test
    void repeatedEmptySelectorsRemainSerializable() {
        for (int i = 0; i < 3; i++) {
            final Dialog dialog = convert(new CustomForm("Title", new DropdownFormElement("Choose")));
            assertDoesNotThrow(() -> BedrockFormDialogConverter.serialize(dialog));
        }
    }

    private static Dialog convert(final Form form) {
        form.setTranslator(Function.identity());
        return BedrockFormDialogConverter.convert(7, form, ProtocolVersion.v1_21_6);
    }

}
