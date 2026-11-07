package codes.castled.allium.item.library;

import codes.castled.allium.item.ItemHmacSigner;
import codes.castled.allium.item.ItemProviderAdapter;
import codes.castled.allium.item.ItemRef;
import codes.castled.allium.item.ItemService;
import codes.castled.allium.item.MockItemStacks;
import codes.castled.allium.item.ProviderKey;
import codes.castled.allium.item.ProviderStatus;
import codes.castled.allium.item.ResolutionResult;
import codes.castled.allium.item.ResolutionStatus;
import codes.castled.allium.item.spec.ItemSpecParser;
import codes.castled.allium.item.spec.RawItemData;
import codes.castled.allium.pack.CoreItems;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.profile.PlayerProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ItemLibraryTest {

    private ItemService items;
    private ItemFactory factory;
    private SkullProfiles skulls;
    private final ItemSpecParser parser = new ItemSpecParser(false);

    @BeforeEach
    void setUp() throws Exception {
        items = new ItemService(MockItemStacks::create, ItemHmacSigner.randomEphemeral(), msg -> { });
        CoreItems.definitions(CoreItems.loadManifest(getClass().getClassLoader())).forEach(items::registerAlliumItem);
        provider("nexo", ProviderStatus.READY, Set.of("mob_disarmer", "ruby"));
        provider("oraxen", ProviderStatus.LOADING, Set.of());
        provider("craftengine", ProviderStatus.FAILED, Set.of());
        items.bindAppearanceToBehavior(ProviderKey.of("nexo", "mob_disarmer"), "allium:mob_disarmer");
        factory = mock(ItemFactory.class);
        skulls = new SkullProfiles(name -> null, name -> mock(PlayerProfile.class), name -> new CompletableFuture<>(),
                100L, 60_000L, 8, System::currentTimeMillis);
    }

    @AfterEach
    void tearDown() {
        skulls.close();
    }

    private void provider(String name, ProviderStatus status, Set<String> ids) {
        NamespacedKey nativeKey = new NamespacedKey(name, "native_id");
        items.registerProvider(new ItemProviderAdapter() {
            public String providerName() { return name; }
            public ProviderStatus status() { return status; }
            public ResolutionResult createItem(String id) {
                if (!ids.contains(id)) return ResolutionResult.unknownId(ProviderKey.of(name, id), name + " has no item " + id);
                ItemStack stack = MockItemStacks.create(Material.DIAMOND_SWORD);
                stack.getItemMeta().getPersistentDataContainer().set(nativeKey, PersistentDataType.STRING, id);
                return ResolutionResult.resolved(ProviderKey.of(name, id), stack);
            }
            public Optional<String> identifyItemId(ItemStack stack) {
                return Optional.ofNullable(stack.getItemMeta().getPersistentDataContainer().get(nativeKey, PersistentDataType.STRING));
            }
        });
    }

    private ItemLibrary library(String version) {
        return new ItemLibrary(items, new ComponentAdapter(ServerVersion.parse(version), factory), skulls, key -> Optional.empty());
    }

    @Test
    void providerKeyResolutionOutcomesSurfaceAsTypedFailuresAndNothingIsBuiltPartially() {
        ItemLibrary library = library("1.21.8-R0.1-SNAPSHOT");
        assertStatus(library, "nexo:unknown", ResolutionStatus.UNKNOWN_ID);
        assertStatus(library, "oraxen:gate_disarmer", ResolutionStatus.PROVIDER_LOADING);
        assertStatus(library, "craftengine:sf_ores:silver_ingot", ResolutionStatus.PROVIDER_FAILED);
        assertStatus(library, "itemsadder:ruby", ResolutionStatus.PROVIDER_ABSENT);
        assertStatus(library, "allium:no_such_tool", ResolutionStatus.UNKNOWN_ID);
        assertStatus(library, "stone,nexo:unknown", ResolutionStatus.UNKNOWN_ID);
        assertEquals(1, library.build(parser.parse("nexo:ruby"), false).items().size());
    }

    @Test
    void disarmersAreMintedOnePerStackWithTheirOwnSignedIdentity() {
        ItemLibrary library = library("26.3.build.141-beta");
        ItemLibrary.Prepared built = library.build(parser.parse("allium:mob_disarmer 3"), false);
        assertEquals(3, built.items().size());
        Set<String> uuids = new HashSet<>();
        for (ItemLibrary.PreparedItem item : built.items()) {
            ItemStack stack = item.stack();
            assertEquals(1, stack.getAmount());
            ItemRef ref = items.identify(stack).orElseThrow();
            assertEquals(Optional.of("allium:mob_disarmer"), ref.behaviorId(), "verified, so signed correctly");
            assertNotNull(stack.getItemMeta().getPersistentDataContainer().get(ItemService.KEY_SIGNATURE, PersistentDataType.STRING));
            uuids.add(stack.getItemMeta().getPersistentDataContainer().get(ItemService.KEY_INSTANCE_UUID, PersistentDataType.STRING));
        }
        assertEquals(3, uuids.size(), "every stack has its own instance UUID");

        ItemStack nexo = library.build(parser.parse("nexo:mob_disarmer"), false).items().get(0).stack();
        ItemRef ref = items.identify(nexo).orElseThrow();
        assertEquals(ProviderKey.of("nexo", "mob_disarmer"), ref.appearance());
        assertEquals(Optional.of("allium:mob_disarmer"), ref.behaviorId(), "the bound provider appearance is stamped and signed");
        assertThrows(ItemBuildException.class, () -> library.build(parser.parse("allium:mob_disarmer 37"), false));
    }

    @Test
    void privilegedBehaviorsNeedThePermission() {
        ItemLibrary library = library("1.20.1-R0.1-SNAPSHOT");
        ItemBuildException.Privileged denied = assertThrows(ItemBuildException.Privileged.class,
                () -> library.build(parser.parse("allium:handcuffs"), false));
        assertEquals("allium:handcuffs", denied.behavior());
        ItemStack cuffs = library.build(parser.parse("allium:handcuffs"), true).items().get(0).stack();
        assertEquals(Optional.of("allium:handcuffs"), items.identify(cuffs).orElseThrow().behaviorId());
    }

    @Test
    void rawDataIsVersionSplitAndVanillaOnly() {
        ItemStack sword = MockItemStacks.create(Material.DIAMOND_SWORD);
        when(factory.createItemStack(anyString())).thenReturn(sword);

        ItemLibrary legacy = library("1.20.1-R0.1-SNAPSHOT");
        ItemBuildException components = assertThrows(ItemBuildException.class,
                () -> legacy.build(parser.parse("diamond_sword[unbreakable={}]"), false));
        assertTrue(components.getMessage().contains("require Minecraft 1.20.5+"), components.getMessage());
        legacy.build(parser.parse("diamond_sword{display:{Name:'{\"text\":\"Blade, of Testing; Mk II\"}'},Unbreakable:1b} 2"), false);
        verify(factory).createItemStack("minecraft:diamond_sword{display:{Name:'{\"text\":\"Blade, of Testing; Mk II\"}'},Unbreakable:1b}");
        assertEquals(2, sword.getAmount());

        ItemLibrary modern = library("1.21.8-R0.1-SNAPSHOT");
        ItemBuildException nbt = assertThrows(ItemBuildException.class, () -> modern.build(parser.parse("diamond_sword{Unbreakable:1b}"), false));
        assertTrue(nbt.getMessage().contains("uses [...] data components"), nbt.getMessage());
        modern.build(parser.parse("diamond_sword[custom_name={text:\"Blade, of Testing; Mk II\"},unbreakable={}]"), false);
        verify(factory).createItemStack("minecraft:diamond_sword[custom_name={text:\"Blade, of Testing; Mk II\"},unbreakable={}]");

        ItemBuildException provider = assertThrows(ItemBuildException.class,
                () -> modern.build(parser.parse("allium:mob_disarmer[unbreakable={}]"), false));
        assertTrue(provider.getMessage().contains("applies only to vanilla items"), provider.getMessage());

        when(factory.createItemStack("minecraft:stick[nope={}]")).thenThrow(new IllegalArgumentException("Unknown component nope"));
        ItemBuildException parse = assertThrows(ItemBuildException.class, () -> modern.build(parser.parse("stick[nope={}]"), false));
        assertTrue(parse.getMessage().contains("Unknown component nope"), parse.getMessage());
        assertEquals(new ComponentAdapter(ServerVersion.parse("1.20.4"), factory).getClass(), ComponentAdapter.class);
        assertEquals(RawItemData.Kind.COMPONENTS, parser.parse("stick[a=1]").items().get(0).raw().orElseThrow().kind());
    }

    @Test
    void serverVersionsSplitAtDataComponents() {
        assertFalse(ServerVersion.parse("1.20.1-R0.1-SNAPSHOT").hasDataComponents());
        assertFalse(ServerVersion.parse("1.20.4-R0.1-SNAPSHOT").hasDataComponents());
        assertTrue(ServerVersion.parse("1.20.5-R0.1-SNAPSHOT").hasDataComponents());
        assertTrue(ServerVersion.parse("1.21.8-R0.1-SNAPSHOT").hasDataComponents());
        assertTrue(ServerVersion.parse("26.3.build.141-beta").hasDataComponents());
        assertEquals("26.3", ServerVersion.parse("26.3.build.141-beta").toString());
        assertEquals("1.20.1", ServerVersion.parse("1.20.1-R0.1-SNAPSHOT").toString());
    }

    @Test
    void tokensRejectWrongTargetsAndUnknownKeys() {
        ItemLibrary library = library("1.21.8-R0.1-SNAPSHOT");
        assertMessage(library, "stone owner:Notch", "owner: applies to player_head");
        assertMessage(library, "stone glitter:5", "unknown token 'glitter'");
        assertMessage(library, "stone name", "name needs a value");
        assertMessage(library, "stone unbreakable:yes", "unbreakable takes no value");
    }

    private void assertMessage(ItemLibrary library, String spec, String fragment) {
        ItemBuildException e = assertThrows(ItemBuildException.class, () -> library.build(parser.parse(spec), false), spec);
        assertTrue(e.getMessage().contains(fragment), spec + " -> " + e.getMessage());
    }

    private void assertStatus(ItemLibrary library, String spec, ResolutionStatus status) {
        ItemBuildException.Unresolved e = assertThrows(ItemBuildException.Unresolved.class, () -> library.build(parser.parse(spec), false), spec);
        assertEquals(status, e.status(), spec + ": " + e.getMessage());
    }

    @Test
    void skullLookupsAreCachedDedupedBoundedAndNeverThrow() throws Exception {
        PlayerProfile resolved = mock(PlayerProfile.class);
        int[] calls = {0};
        CompletableFuture<PlayerProfile> remote = new CompletableFuture<>();
        try (SkullProfiles profiles = new SkullProfiles(name -> null, name -> mock(PlayerProfile.class), name -> {
            calls[0]++;
            return remote;
        }, 2_000L, 60_000L, 2, System::currentTimeMillis)) {
            CompletableFuture<SkullProfiles.Resolved> a = profiles.resolve("Notch");
            CompletableFuture<SkullProfiles.Resolved> b = profiles.resolve("notch");
            assertSame(a, b, "concurrent lookups for one name share a future");
            remote.complete(resolved);
            assertSame(resolved, a.get().profile());
            assertNull(a.get().warning());
            assertSame(resolved, profiles.resolve("NOTCH").get().profile(), "cached");
            assertEquals(1, calls[0]);
            assertThrows(ItemBuildException.class, () -> profiles.resolve("bad name!"));
        }
        // A lookup that never answers times out into a warning, not an exception.
        SkullProfiles.Resolved timedOut = skulls.resolve("Dinnerbone").get();
        assertNotNull(timedOut.profile());
        assertTrue(timedOut.warning().contains("timed out after 100 ms"), timedOut.warning());
    }
}
