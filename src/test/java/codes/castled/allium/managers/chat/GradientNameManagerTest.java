package codes.castled.allium.managers.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradientNameManagerTest {

    @Test
    void solidGradientPlusColorUsesItsNearestAccentAsTheSecondStop() {
        assertEquals(
                "<gradient:#965CEA:blue:0>ze_flash</gradient>",
                GradientNameManager.buildAnimatedGradientText(
                        "ze_flash",
                        List.of("#965CEA", "#965CEA", "#965CEA"),
                        "0"
                )
        );
    }

    @Test
    void gradientPlusEndpointsProduceATwoStopAnimatedGradient() {
        assertEquals(
                "<gradient:#CB2D3E:#EF473A:-0.1>Player</gradient>",
                GradientNameManager.buildAnimatedGradientText(
                        "Player",
                        List.of("#CB2D3E", "#D8373D", "#E5413B", "#EF473A"),
                        "-0.1"
                )
        );
    }

    @Test
    void twoStopGradientMovesSmoothlyAcrossTheMiddleOfThePhaseCycle() {
        List<String> sourceColors = List.of("#CB2D3E", "#D8373D", "#E5413B", "#EF473A");
        List<Integer> before = renderedColors(sourceColors, "-0.1");
        List<Integer> middle = renderedColors(sourceColors, "0");
        List<Integer> after = renderedColors(sourceColors, "0.1");

        double intoMiddle = colorDistance(before, middle);
        double outOfMiddle = colorDistance(middle, after);

        assertTrue(intoMiddle > 0);
        assertEquals(outOfMiddle, intoMiddle, 1.0);
    }

    @Test
    void solidColorAccentAlsoMovesSmoothlyAcrossTheMiddleOfThePhaseCycle() {
        List<String> sourceColors = List.of("#965CEA", "#965CEA", "#965CEA");
        List<Integer> before = renderedColors(sourceColors, "-0.1");
        List<Integer> middle = renderedColors(sourceColors, "0");
        List<Integer> after = renderedColors(sourceColors, "0.1");

        double intoMiddle = colorDistance(before, middle);
        double outOfMiddle = colorDistance(middle, after);

        assertTrue(intoMiddle > 0);
        assertEquals(outOfMiddle, intoMiddle, 1.0);
    }

    private List<Integer> renderedColors(List<String> sourceColors, String phase) {
        Component component = MiniMessage.miniMessage().deserialize(
                GradientNameManager.buildAnimatedGradientText("ze_flash", sourceColors, phase)
        );
        List<Integer> colors = new ArrayList<>();
        collectColors(component, null, colors);
        return colors;
    }

    private void collectColors(Component component, TextColor inherited, List<Integer> colors) {
        TextColor color = component.color() == null ? inherited : component.color();
        if (component instanceof TextComponent text && !text.content().isEmpty() && color != null) {
            text.content().codePoints().forEach(ignored -> colors.add(color.value()));
        }
        for (Component child : component.children()) {
            collectColors(child, color, colors);
        }
    }

    private double colorDistance(List<Integer> first, List<Integer> second) {
        assertEquals(first.size(), second.size());
        long squaredDistance = 0;
        for (int index = 0; index < first.size(); index++) {
            int firstColor = first.get(index);
            int secondColor = second.get(index);
            for (int shift : List.of(16, 8, 0)) {
                int delta = ((firstColor >> shift) & 0xFF) - ((secondColor >> shift) & 0xFF);
                squaredDistance += (long) delta * delta;
            }
        }
        return Math.sqrt(squaredDistance);
    }
}
