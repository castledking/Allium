package codes.castled.allium.util;

import java.lang.reflect.Method;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentBuilder;
import net.kyori.adventure.text.JoinConfiguration;

/**
 * Component building that links on every supported server.
 *
 * <p>Adventure 5 re-bounded {@code ComponentBuilder}'s type parameter from
 * {@code BuildableComponent} to {@code Component}. {@code TextComponent.Builder} declares no
 * {@code build()} of its own, so a chain ending in {@code build()} resolves against
 * {@code ComponentBuilder} and inherits whichever bound it was compiled against: a jar built
 * against Adventure 5 emits {@code build()LComponent;}, and on Adventure 4 — which Minecraft
 * 1.21 servers still ship, and which is the oldest Adventure the plugin claims to support —
 * that method does not exist. It fails as a {@link NoSuchMethodError} the first time the line
 * is drawn, which took out {@code /cards give} on a 1.21 server with the card frame the first
 * thing it tried to render.
 *
 * <p>No choice of static type avoids this, because the return descriptor comes from the bound
 * rather than from the receiver: declaring the variable as {@code ComponentBuilder} and calling
 * {@code build()} still resolves against the bound of the version compiled against, and
 * compiling against the older Adventure breaks 26.x symmetrically. What survives a change of
 * bound is a static method with a non-generic return, which is why everything here is built on
 * {@link Component#join}.
 *
 * <p>Separate from {@link ApiCompat} rather than added to it, because that class resolves
 * registry entries at class-initialisation time and needs a live server to do it. The frame and
 * card lore renderers are unit tested without one, so they must not reach a class whose static
 * initialiser does.
 *
 * <p>Every method here produces the same component tree the equivalent builder chain did. That
 * is load-bearing rather than cosmetic: the frame glyph has to stay its own child, because it
 * carries the frame's font while the text beside it carries the line's, and
 * {@link codes.castled.allium.tradingcards.item.CardFrame#unwrap} has to be able to tell the
 * two apart again.
 */
public final class ComponentCompat {

    private ComponentCompat() {}

    /** Concatenation with nothing between the parts. */
    private static final JoinConfiguration NO_SEPARATOR = JoinConfiguration.noSeparators();

    /**
     * {@code ComponentBuilder.build()}, looked up once.
     *
     * <p>Reached only from {@link #wrap(List)}, which is the one shape {@link #join(List)}
     * cannot produce. Falls back to the bare child if the lookup fails, which renders the same
     * and cannot fail a render over.
     */
    private static final Method COMPONENT_BUILDER_BUILD = componentBuilderBuild();

    private static Method componentBuilderBuild() {
        try {
            return ComponentBuilder.class.getMethod("build");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Two components side by side, as one parent with them as its two children.
     *
     * <p>The frame-line case: a glyph that must keep its own font, followed by text that must
     * not inherit it.
     */
    public static Component join(Component first, Component second) {
        return Component.join(NO_SEPARATOR, first, second);
    }

    /**
     * Concatenation of {@code parts}, nothing between them.
     *
     * <p>A single part comes back as itself rather than wrapped, so a line made of one run needs
     * no parent. Use {@link #wrap(List)} where the parent itself is the point.
     */
    public static Component join(List<? extends Component> parts) {
        return Component.join(NO_SEPARATOR, parts);
    }

    /**
     * Concatenation of {@code parts} with {@code separator} between each pair.
     *
     * <p>For text that is one component spread over several lines — {@link
     * JoinConfiguration#newlines()} puts a break between them without the caller appending the
     * separators by hand.
     */
    public static Component join(JoinConfiguration separator, List<? extends Component> parts) {
        return Component.join(separator, parts);
    }

    /**
     * A parent whose children are exactly {@code children}, whatever the count.
     *
     * <p>Unlike {@link #join(List)}, this keeps the parent for one child or none. Callers that
     * take a prefix off a line depend on the remainder staying a line: {@code title} reads the
     * first child back off it, and a bare child would arrive with nothing to read.
     */
    public static Component wrap(List<? extends Component> children) {
        if (children.isEmpty()) return Component.empty();
        if (children.size() > 1) return Component.join(NO_SEPARATOR, children);
        return buildParent(children.get(0));
    }

    private static Component buildParent(Component only) {
        Method build = COMPONENT_BUILDER_BUILD;
        if (build == null) return only;
        try {
            ComponentBuilder<?, ?> builder = Component.text();
            builder.append(only);
            return (Component) build.invoke(builder);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return only;
        }
    }
}
