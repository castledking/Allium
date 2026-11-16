package codes.castled.allium.chat;

import codes.castled.allium.PluginStart;
import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.*;
import github.scarsz.discordsrv.dependencies.jda.api.entities.*;
import github.scarsz.discordsrv.dependencies.jda.api.hooks.ListenerAdapter;
import github.scarsz.discordsrv.dependencies.jda.api.events.message.guild.GuildMessageDeleteEvent;
import github.scarsz.discordsrv.dependencies.okhttp3.*;
import github.scarsz.discordsrv.dependencies.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;
import java.io.IOException;

/** Loaded only with DiscordSRV. Correlation uses returned IDs, never text/history searches. */
public final class DiscordChatRelay implements ChatRelay {
    private final PluginStart plugin;private final ChatService service;
    interface Runtime {
        github.scarsz.discordsrv.dependencies.jda.api.JDA jda();
        String user(UUID id);String webhook(TextChannel channel);
        void subscribe(Object listener);void unsubscribe(Object listener);
    }
    private static final class DiscordRuntime implements Runtime {
        public github.scarsz.discordsrv.dependencies.jda.api.JDA jda(){return DiscordSRV.getPlugin().getJda();}
        public String user(UUID id){return DiscordSRV.getPlugin().getAccountLinkManager().getDiscordId(id);}
        public String webhook(TextChannel channel){return github.scarsz.discordsrv.util.WebhookUtil.getWebhookUrlToUseForChannel(channel);}
        public void subscribe(Object listener){DiscordSRV.api.subscribe(listener);}
        public void unsubscribe(Object listener){DiscordSRV.api.unsubscribe(listener);}
    }
    private final Runtime backend;
    private final Executor worker;
    private final LinkedHashMap<String,Runnable> linked=new LinkedHashMap<>();
    private volatile boolean closed;
    private Object hookedJda;
    private final ListenerAdapter deletes=new ListenerAdapter(){@Override public void onGuildMessageDelete(GuildMessageDeleteEvent event){service.discordDeleted(event.getMessageId());}};
    public DiscordChatRelay(PluginStart plugin,ChatService service){this(plugin,service,new DiscordRuntime());}
    DiscordChatRelay(PluginStart plugin,ChatService service,Runtime backend){
        this.plugin=plugin;this.service=service;this.backend=backend;worker=plugin.getScheduler().boundedPool("Allium-Chat-Relay",1,64);backend.subscribe(this);
        hookDeletes();
    }
    @Subscribe public void ready(DiscordReadyEvent event){hookDeletes();}
    private synchronized void hookDeletes(){var jda=backend.jda();if(!closed&&jda!=null&&hookedJda!=jda){if(hookedJda instanceof github.scarsz.discordsrv.dependencies.jda.api.JDA previous)previous.removeEventListener(deletes);jda.addEventListener(deletes);hookedJda=jda;}}
    @Subscribe public void preventDefaultRelay(GameChatMessagePreProcessEvent event){if(service.ownsChat())event.setCancelled(true);}
    @Subscribe public void incoming(DiscordGuildMessagePreProcessEvent event){
        if(closed||event.isCancelled()||!service.ownsChat())return;
        var channel=service.settings().channels().values().stream().filter(c->!c.local()&&c.discordId().equals(event.getChannel().getId())).findFirst().orElse(null);if(channel==null)return;
        event.setCancelled(true);if(event.getAuthor().isBot()||event.getMessage().isWebhookMessage())return;
        service.inbound(event.getMessage().getId(),event.getChannel().getId(),event.getMember()==null?event.getAuthor().getName():event.getMember().getEffectiveName(),event.getMessage().getContentDisplay());
    }
    @Override public void send(ChatHistory.Message message,ChatSettings.Channel channel,String player,String body){
        if(closed||channel.local()||channel.discordId().isEmpty()||message.deleted().get())return;
        var jda=backend.jda();if(jda==null){failed(message.id());return;}
        TextChannel destination=jda.getTextChannelById(channel.discordId());if(destination==null){failed(message.id());return;}
        String content=channel.discordOut().replace("{player}",player).replace("{message}",body);
        if(content.length()>1900)content=content.substring(0,1900);
        if(!channel.webhook()){
            destination.sendMessage(content).allowedMentions(List.of()).queue(sent->plugin.getScheduler().runOnServer(()->link(message,()->sent.delete().queue(ok->{},error->failed(message.id())))),error->failed(message.id()));
        }else{
            final String frozen=content;
            try{worker.execute(()->{
                try{
                    if(closed||message.deleted().get())return;
                    String url=backend.webhook(destination);if(url==null){failed(message.id());return;}
                    var json=new JSONObject();json.put("content",frozen);String discordUserId=backend.user(message.sender());
                    var profile=discordUserId==null?null:jda.getUserById(discordUserId);
                    json.put("username",profile==null?player:profile.getName());if(profile!=null)json.put("avatar_url",profile.getEffectiveAvatarUrl());json.put("allowed_mentions",new JSONObject().put("parse",new ArrayList<>()));
                    var request=new Request.Builder().url(url+"?wait=true").post(RequestBody.create(MediaType.parse("application/json"),json.toString())).build();
                    jda.getHttpClient().newCall(request).enqueue(new Callback(){
                        public void onFailure(Call call,IOException error){failed(message.id());}
                        public void onResponse(Call call,Response response)throws IOException{
                            try(response){if(!response.isSuccessful()||response.body()==null){failed(message.id());return;}
                                var saved=new JSONObject(response.body().string());String id=saved.optString("id","");if(!id.matches("[0-9]{5,24}")||!channel.discordId().equals(saved.optString("channel_id",channel.discordId()))){failed(message.id());return;}
                                plugin.getScheduler().runOnServer(()->link(message,()->jda.getHttpClient().newCall(new Request.Builder().url(url+"/messages/"+id).delete().build()).enqueue(new Callback(){
                                    public void onFailure(Call call,IOException error){failed(message.id());}
                                    public void onResponse(Call call,Response r){try(r){if(!r.isSuccessful())failed(message.id());}}
                                })));
                            }
                        }
                    });
                }catch(Exception error){failed(message.id());}
            });}catch(RejectedExecutionException error){failed(message.id());}
        }
    }
    private void link(ChatHistory.Message message,Runnable remove){
        if(message.deleted().get()){remove.run();return;}if(closed)return;
        linked.put(message.id(),remove);while(linked.size()>4096)linked.remove(linked.keySet().iterator().next());
    }
    @Override public void delete(String id){Runnable action=linked.remove(id);if(action!=null)action.run();}
    private void failed(String id){plugin.logger().warning("Discord chat relay operation unavailable for "+id+"; no automatic resend.");}
    @Override public void close(){closed=true;backend.unsubscribe(this);var jda=backend.jda();if(jda!=null)jda.removeEventListener(deletes);linked.clear();}
}
