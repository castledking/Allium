package codes.castled.allium.chat;

import codes.castled.allium.PluginStart;
import codes.castled.allium.scheduler.*;
import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.events.*;
import github.scarsz.discordsrv.dependencies.jda.api.*;
import github.scarsz.discordsrv.dependencies.jda.api.entities.*;
import github.scarsz.discordsrv.dependencies.jda.api.requests.restaction.MessageAction;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Uses the pinned actual SDK types with mocked transports; never contacts Discord. */
class DiscordChatRelayTest {
    PluginStart plugin;ChatService service;DiscordChatRelay relay;DiscordChatRelay.Runtime backend;JDA jda;TextChannel destination;
   MessageAction action;AtomicReference<Consumer<Message>> ack;
    @BeforeEach void setup(){
        backend=mock(DiscordChatRelay.Runtime.class);
        jda=mock(JDA.class);when(backend.jda()).thenReturn(jda);destination=mock(TextChannel.class);when(jda.getTextChannelById("123456789")).thenReturn(destination);
        plugin=mock(PluginStart.class);when(plugin.logger()).thenReturn(mock(codes.castled.allium.util.AlliumLogger.class));var scheduler=mock(TaskScheduler.class);when(plugin.getScheduler()).thenReturn(scheduler);
        var pool=mock(ThreadPoolExecutor.class);when(scheduler.boundedPool(anyString(),anyInt(),anyInt())).thenReturn(pool);doAnswer(call->{((Runnable)call.getArgument(0)).run();return null;}).when(pool).execute(any());
        when(scheduler.runOnServer(any())).thenAnswer(call->{((Runnable)call.getArgument(0)).run();return TaskHandle.NONE;});
        service=mock(ChatService.class);when(service.ownsChat()).thenReturn(true);
        var config=new org.bukkit.configuration.file.YamlConfiguration();config.set("chat.channels.list.default.discord-channel-id","123456789");when(service.settings()).thenReturn(ChatSettings.read(config));
        action=mock(MessageAction.class);when(destination.sendMessage(anyString())).thenReturn(action);when(action.allowedMentions(anyCollection())).thenReturn(action);
        ack=new AtomicReference<>();doAnswer(call->{ack.set(call.getArgument(0));return null;}).when(action).queue(any(Consumer.class),any(Consumer.class));
        relay=new DiscordChatRelay(plugin,service,backend);
    }
    ChatHistory.Message message(){return new ChatHistory.Message("run:1",UUID.randomUUID(),"Alice","default",0,Map.of(),new AtomicBoolean());}
    @Test void returnedMessageIdentityLinksDeletionWithoutTextMatching(){var m=message();relay.send(m,service.settings().channels().get("default"),"Alice","repeated text");var sent=mock(Message.class,RETURNS_DEEP_STUBS);ack.get().accept(sent);relay.delete(m.id());verify(sent).delete();verify(destination,never()).getHistory();verify(action).allowedMentions(List.of());}
    @Test void deletionBeforeAcknowledgementDeletesLateCopy(){var m=message();relay.send(m,service.settings().channels().get("default"),"Alice","late");m.deleted().set(true);relay.delete(m.id());var sent=mock(Message.class,RETURNS_DEEP_STUBS);ack.get().accept(sent);verify(sent).delete();}
    @Test void localAndUnconfiguredChannelsNeverRelay(){var m=message();relay.send(m,service.settings().channels().get("local"),"Alice","secret local");relay.send(m,service.settings().channels().get("staff-chat"),"Alice","unconfigured staff");verify(destination,never()).sendMessage(anyString());}
    @Test void localChannelHonorsExcludeFromDiscordSetting(){
        var excludedConfig=new org.bukkit.configuration.file.YamlConfiguration();
        excludedConfig.set("chat.channels.local-channel.exclude-from-discord",true);
        excludedConfig.set("chat.channels.local-channel.discord-channel-id","123456789");
        var excludedLocal=ChatSettings.read(excludedConfig).channels().get("local");
        assertTrue(excludedLocal.excludeFromDiscord());
        relay.send(message(),excludedLocal,"Alice","hidden local");
        verify(destination,never()).sendMessage(anyString());

        var optInConfig=new org.bukkit.configuration.file.YamlConfiguration();
        optInConfig.set("chat.channels.local-channel.exclude-from-discord",false);
        optInConfig.set("chat.channels.local-channel.discord-channel-id","123456789");
        var optInLocal=ChatSettings.read(optInConfig).channels().get("local");
        assertFalse(optInLocal.excludeFromDiscord());
        relay.send(message(),optInLocal,"Alice","opted in local");
        verify(destination,times(1)).sendMessage("Alice: opted in local");
    }
    @Test void inboundOnlyInterceptsConfiguredChannelAndSkipsBots(){var event=mock(DiscordGuildMessagePreProcessEvent.class,RETURNS_DEEP_STUBS);when(event.getChannel().getId()).thenReturn("123456789");when(event.getAuthor().isBot()).thenReturn(true);relay.incoming(event);verify(event).setCancelled(true);verify(service,never()).inbound(anyString(),anyString(),anyString(),anyString());}
    @Test void unrelatedDiscordChannelIsLeftToItsOwner(){var event=mock(DiscordGuildMessagePreProcessEvent.class,RETURNS_DEEP_STUBS);when(event.getChannel().getId()).thenReturn("999999999");relay.incoming(event);verify(event,never()).setCancelled(anyBoolean());verify(service,never()).inbound(anyString(),anyString(),anyString(),anyString());}
    @Test void missingTransportDoesNotPretendToSendOrRetry(){when(backend.jda()).thenReturn(null);relay.send(message(),service.settings().channels().get("default"),"Alice","text");verify(destination,never()).sendMessage(anyString());verify(plugin.logger()).warning(contains("no automatic resend"));}
    @Test void defaultDiscordSrvRelayIsSuppressedOnlyWhenAlliumOwnsChat(){var event=mock(GameChatMessagePreProcessEvent.class);relay.preventDefaultRelay(event);verify(event).setCancelled(true);reset(event);when(service.ownsChat()).thenReturn(false);relay.preventDefaultRelay(event);verifyNoInteractions(event);}

    @Test void webhookUsesAcknowledgedIdAndSuppressesMentions()throws Exception{
        var client=mock(github.scarsz.discordsrv.dependencies.okhttp3.OkHttpClient.class);
        when(jda.getHttpClient()).thenReturn(client);when(backend.webhook(destination)).thenReturn("https://example.invalid/api/webhooks/1/test");
        var calls=new ArrayList<github.scarsz.discordsrv.dependencies.okhttp3.Request>();
        var acknowledgements=new ArrayList<github.scarsz.discordsrv.dependencies.okhttp3.Callback>();
        when(client.newCall(any())).thenAnswer(invocation->{calls.add(invocation.getArgument(0));var call=mock(github.scarsz.discordsrv.dependencies.okhttp3.Call.class);doAnswer(c->{acknowledgements.add(c.getArgument(0));return null;}).when(call).enqueue(any());return call;});
        var base=service.settings().channels().get("default");var channel=new ChatSettings.Channel(base.id(),base.display(),base.aliases(),base.format(),0,base.discordId(),base.discordIn(),base.discordOut(),true);
        var m=message();relay.send(m,channel,"Alice","@everyone body");assertEquals(1,calls.size());assertEquals("true",calls.get(0).url().queryParameter("wait"));
        var bytes=new github.scarsz.discordsrv.dependencies.okio.Buffer();calls.get(0).body().writeTo(bytes);var payload=new github.scarsz.discordsrv.dependencies.json.JSONObject(bytes.readUtf8());assertEquals(0,payload.getJSONObject("allowed_mentions").getJSONArray("parse").length());
        assertEquals("@everyone body",payload.getString("content"));
        var response=new github.scarsz.discordsrv.dependencies.okhttp3.Response.Builder().request(calls.get(0)).protocol(github.scarsz.discordsrv.dependencies.okhttp3.Protocol.HTTP_1_1).code(200).message("OK").body(github.scarsz.discordsrv.dependencies.okhttp3.ResponseBody.create(github.scarsz.discordsrv.dependencies.okhttp3.MediaType.parse("application/json"),"{\"id\":\"444444444\",\"channel_id\":\"123456789\"}")).build();
        m.deleted().set(true);relay.delete(m.id());acknowledgements.get(0).onResponse(null,response);
        assertEquals(2,calls.size());assertEquals("DELETE",calls.get(1).method());assertEquals("/api/webhooks/1/test/messages/444444444",calls.get(1).url().encodedPath());verify(destination,never()).getHistory();
    }

    @Test void discordSrvWebhookDeliveryUsesFormattedUsernamePlayerHeadAndCleanBody()throws Exception{
        var client=mock(github.scarsz.discordsrv.dependencies.okhttp3.OkHttpClient.class);
        when(jda.getHttpClient()).thenReturn(client);
        when(backend.webhookDeliveryEnabled()).thenReturn(true);
        when(backend.webhook(destination)).thenReturn("https://example.invalid/api/webhooks/1/test");
        when(backend.displayName(any(),eq("Jack"))).thenReturn("§c[ADMIN]§fJack");
        when(backend.formatWebhook(any(),eq("Jack"),eq("§c[ADMIN]§fJack"),any(),eq(destination),eq("gotta update server to 26.3")))
                .thenReturn(new DiscordChatRelay.WebhookPayload(
                        "[ADMIN]Jack",
                        "https://crafthead.net/helm/abc123/128",
                        "gotta update server to 26.3",
                        false));
        var calls=new ArrayList<github.scarsz.discordsrv.dependencies.okhttp3.Request>();
        when(client.newCall(any())).thenAnswer(invocation->{
            calls.add(invocation.getArgument(0));
            return mock(github.scarsz.discordsrv.dependencies.okhttp3.Call.class);
        });
        var defaultChannel=service.settings().channels().get("default");
        assertFalse(defaultChannel.webhook());
        var m=message();
        relay.send(m,defaultChannel,"Jack","gotta update server to 26.3");
        verify(destination,never()).sendMessage(anyString());
        assertEquals(1,calls.size());
        var bytes=new github.scarsz.discordsrv.dependencies.okio.Buffer();
        calls.get(0).body().writeTo(bytes);
        var payload=new github.scarsz.discordsrv.dependencies.json.JSONObject(bytes.readUtf8());
        assertEquals("[ADMIN]Jack",payload.getString("username"));
        assertEquals("https://crafthead.net/helm/abc123/128",payload.getString("avatar_url"));
        assertEquals("gotta update server to 26.3",payload.getString("content"));
    }

    @Test void finalizeWebhookPayloadKeepsMinecraftHeadAndFormattedNameUnlessDiscordOverrideEnabled(){
        String head="https://crafthead.net/helm/0123456789/128";
        var linkedDefault=DiscordChatRelay.finalizeWebhookPayload(
                "[ADMIN]Jack",head,"hello world","Jack","retrogameman88",
                Map.of(),"retogameman","retogameman","https://cdn.discordapp.com/avatars/1/a.png",
                false,false);
        assertFalse(linkedDefault.filtered());
        assertEquals("[ADMIN]Jack",linkedDefault.username());
        assertEquals(head,linkedDefault.avatarUrl());
        assertEquals("hello world",linkedDefault.content());

        var unlinked=DiscordChatRelay.finalizeWebhookPayload(
                "ggpots",head,"a","ggpots","ggpots",
                Map.of(),null,null,null,false,false);
        assertFalse(unlinked.filtered());
        assertEquals("ggpots",unlinked.username());
        assertEquals(head,unlinked.avatarUrl());
        assertEquals("a",unlinked.content());

        var discordOverrides=DiscordChatRelay.finalizeWebhookPayload(
                "[ADMIN]Jack",head,"hello world","Jack","retrogameman88",
                Map.of(),"RetroNick","retogameman","https://cdn.discordapp.com/avatars/1/a.png",
                true,true);
        assertEquals("RetroNick",discordOverrides.username());
        assertEquals("https://cdn.discordapp.com/avatars/1/a.png",discordOverrides.avatarUrl());
    }

    @Test void emptyDiscordChannelIdFallsBackToDiscordSrvGameChannelMapping(){
        var mapped=mock(TextChannel.class);
        when(mapped.getId()).thenReturn("555555555");
        when(mapped.sendMessage(anyString())).thenReturn(action);
        var staff=service.settings().channels().get("staff-chat");
        assertTrue(staff.discordId().isEmpty());
        when(backend.mappedChannel(staff,"default")).thenReturn(mapped);
        relay.send(message(),staff,"Alice","staff ping");
        verify(mapped,times(1)).sendMessage("Alice: staff ping");
    }

    @Test void readyHookAttachesDeleteListenerAfterDelayedConnection(){relay.close();when(backend.jda()).thenReturn(null);relay=new DiscordChatRelay(plugin,service,backend);clearInvocations(jda);when(backend.jda()).thenReturn(jda);relay.ready(mock(DiscordReadyEvent.class));relay.ready(mock(DiscordReadyEvent.class));verify(jda,times(1)).addEventListener(any());}
    @AfterEach void close(){if(relay!=null)relay.close();}
}
