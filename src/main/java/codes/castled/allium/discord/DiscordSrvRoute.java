package codes.castled.allium.discord;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.dependencies.jda.api.EmbedBuilder;
import github.scarsz.discordsrv.dependencies.jda.api.JDA;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Message;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Role;
import github.scarsz.discordsrv.dependencies.jda.api.entities.TextChannel;
import github.scarsz.discordsrv.dependencies.jda.api.requests.restaction.MessageAction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * Posts a staff feed through DiscordSRV's bot. Loaded only while DiscordSRV runs, so no DiscordSRV
 * class is touched without it.
 */
final class DiscordSrvRoute {

    /**
     * Sends {@code post} to {@code channel} (an ID or a DiscordSRV channel name). Returns false when
     * DiscordSRV is not connected or has no such channel, so the caller can use the webhook instead.
     */
    boolean send(String channel, StaffPost post, @Nullable String footer, List<String> roleIds, Consumer<String> failed) {
        try {
            DiscordSRV srv = DiscordSRV.getPlugin();
            JDA jda = srv == null ? null : srv.getJda();
            if (jda == null) return false;
            TextChannel destination = StaffPost.isRoleId(channel) ? jda.getTextChannelById(channel)
                    : srv.getDestinationTextChannelForGameChannelName(channel);
            if (destination == null) return false;
            EmbedBuilder embed = new EmbedBuilder().setTitle(post.title()).setColor(post.color()).setTimestamp(post.timestamp());
            if (post.description() != null) embed.setDescription(post.description());
            for (StaffPost.Field field : post.fields()) embed.addField(field.name(), field.value(), field.inline());
            if (footer != null && !footer.isBlank()) embed.setFooter(StaffPost.cut(footer, 2048));
            MessageAction action = destination.sendMessageEmbeds(embed.build()).allowedMentions(EnumSet.noneOf(Message.MentionType.class));
            List<Role> roles = new ArrayList<>();
            for (String id : roleIds) {
                Role role = jda.getRoleById(id);
                if (role != null) roles.add(role);
            }
            if (!roles.isEmpty()) action = action.content(StaffPost.mentions(roleIds)).mention(roles);
            action.queue(sent -> { }, error -> failed.accept(String.valueOf(error.getMessage())));
            return true;
        } catch (RuntimeException | LinkageError unavailable) {
            failed.accept(String.valueOf(unavailable.getMessage()));
            return false;
        }
    }
}
