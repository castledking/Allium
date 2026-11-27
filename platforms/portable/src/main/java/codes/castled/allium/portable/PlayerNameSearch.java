package codes.castled.allium.portable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/** Ranks already-visible online player identities across Bukkit runtimes. */
public final class PlayerNameSearch {
    private static final int MAX_FUZZY_TOKEN_LENGTH = 64;

    private static final class RankedCandidate<P> {
        private final P player;
        private final int tier;
        private final int score;
        private final int length;
        private final int matchIndex;
        private final String name;

        private RankedCandidate(P player, int tier, int score, int length, int matchIndex, String name) {
            this.player = player;
            this.tier = tier;
            this.score = score;
            this.length = length;
            this.matchIndex = matchIndex;
            this.name = name;
        }
    }

    private static final Comparator<RankedCandidate<?>> RANK_ORDER = Comparator
            .comparingInt((RankedCandidate<?> candidate) -> candidate.tier)
            .thenComparingInt(candidate -> candidate.score)
            .thenComparingInt(candidate -> candidate.length)
            .thenComparingInt(candidate -> candidate.matchIndex)
            .thenComparing(candidate -> candidate.name, String.CASE_INSENSITIVE_ORDER);

    private PlayerNameSearch() {}

    public static <P> List<P> rankedMatches(
            Collection<? extends P> onlinePlayers,
            String query,
            Predicate<P> filter,
            Function<P, UUID> idProvider,
            Function<P, String> nameProvider,
            Function<P, String> nicknameProvider
    ) {
        if (onlinePlayers == null || onlinePlayers.isEmpty() || query == null || query.codePoints().allMatch(Character::isWhitespace)) {
            return Collections.emptyList();
        }
        String trimmed = query.trim();
        String q = trimmed.toLowerCase(Locale.ROOT);
        UUID parsedUuid = tryParseUuid(trimmed);

        List<RankedCandidate<P>> matches = new ArrayList<>();
        for (P candidate : onlinePlayers) {
            if (candidate == null || !filter.test(candidate)) {
                continue;
            }
            String name = nameProvider.apply(candidate);
            if (name == null || name.isEmpty()) {
                continue;
            }
            String nick = nicknameProvider.apply(candidate);
            if (nick == null) nick = "";
            if (nick.equalsIgnoreCase(name)) {
                nick = "";
            }
            RankedCandidate<P> evaluated = evaluate(candidate, name, nick, q, parsedUuid, idProvider);
            if (evaluated != null) {
                matches.add(evaluated);
            }
        }
        if (matches.isEmpty()) {
            return Collections.emptyList();
        }
        matches.sort(RANK_ORDER);
        List<P> ordered = new ArrayList<>(matches.size());
        for (RankedCandidate<P> ranked : matches) {
            ordered.add(ranked.player);
        }
        return Collections.unmodifiableList(ordered);
    }

    private static <P> RankedCandidate<P> evaluate(
            P player,
            String realName,
            String strippedNick,
            String q,
            UUID parsedUuid,
            Function<P, UUID> idProvider
    ) {
        if (parsedUuid != null && parsedUuid.equals(idProvider.apply(player))) {
            return new RankedCandidate<>(player, 0, 0, realName.length(), 0, realName);
        }
        String nameLower = realName.toLowerCase(Locale.ROOT);
        String nickLower = strippedNick.toLowerCase(Locale.ROOT);

        // 1. Exact real name
        if (nameLower.equals(q)) {
            return new RankedCandidate<>(player, 1, 0, realName.length(), 0, realName);
        }
        // 2. Exact stripped nickname
        if (!nickLower.isEmpty() && nickLower.equals(q)) {
            return new RankedCandidate<>(player, 2, 0, nickLower.length(), 0, realName);
        }
        // 3. Real-name prefix
        if (nameLower.startsWith(q)) {
            return new RankedCandidate<>(player, 3, 0, nameLower.length(), 0, realName);
        }
        // 4. Nickname prefix
        if (!nickLower.isEmpty() && nickLower.startsWith(q)) {
            return new RankedCandidate<>(player, 4, 0, nickLower.length(), 0, realName);
        }
        // 5. Real-name substring contains (e.g. "nasty" -> "MrNasty0627")
        if (q.length() >= 2) {
            int idx = nameLower.indexOf(q);
            if (idx >= 0) {
                return new RankedCandidate<>(player, 5, 0, nameLower.length(), idx, realName);
            }
        }
        // 6. Nickname substring contains (e.g. "nasty" -> nick "MrNasty")
        if (q.length() >= 2 && !nickLower.isEmpty()) {
            int idx = nickLower.indexOf(q);
            if (idx >= 0) {
                return new RankedCandidate<>(player, 6, 0, nickLower.length(), idx, realName);
            }
        }

        if (q.length() < 3 || q.length() > MAX_FUZZY_TOKEN_LENGTH) {
            return null;
        }

        // 7. Full-string Damerau-Levenshtein (typos against full username or nickname)
        int bestFull = Integer.MAX_VALUE;
        int bestFullLen = Integer.MAX_VALUE;
        int nameFullDist = fullFuzzyDistance(q, nameLower);
        if (nameFullDist >= 0) {
            bestFull = nameFullDist;
            bestFullLen = nameLower.length();
        }
        if (!nickLower.isEmpty()) {
            int nickFullDist = fullFuzzyDistance(q, nickLower);
            if (nickFullDist >= 0 && (nickFullDist < bestFull
                    || (nickFullDist == bestFull && nickLower.length() < bestFullLen))) {
                bestFull = nickFullDist;
                bestFullLen = nickLower.length();
            }
        }
        if (bestFull != Integer.MAX_VALUE) {
            return new RankedCandidate<>(player, 7, bestFull, bestFullLen, 0, realName);
        }

        // 8. Substring-window Damerau-Levenshtein (typos inside a longer username/nickname, e.g. "nastyy" -> "MrNasty0627")
        if (q.length() >= 4) {
            int bestSub = Integer.MAX_VALUE;
            int bestSubLen = Integer.MAX_VALUE;
            int nameSubDist = substringFuzzyDistance(q, nameLower);
            if (nameSubDist >= 0) {
                bestSub = nameSubDist;
                bestSubLen = nameLower.length();
            }
            if (!nickLower.isEmpty()) {
                int nickSubDist = substringFuzzyDistance(q, nickLower);
                if (nickSubDist >= 0 && (nickSubDist < bestSub
                        || (nickSubDist == bestSub && nickLower.length() < bestSubLen))) {
                    bestSub = nickSubDist;
                    bestSubLen = nickLower.length();
                }
            }
            if (bestSub != Integer.MAX_VALUE) {
                return new RankedCandidate<>(player, 8, bestSub, bestSubLen, 0, realName);
            }
        }

        return null;
    }

    private static int fullFuzzyDistance(String query, String candidate) {
        if (candidate.isEmpty() || candidate.length() > MAX_FUZZY_TOKEN_LENGTH) {
            return -1;
        }
        int maxDist = Math.min(3, Math.max(1, (Math.min(query.length(), candidate.length()) + 1) / 3));
        if (Math.abs(query.length() - candidate.length()) > maxDist) {
            return -1;
        }
        int dist = damerauLevenshtein(query, candidate);
        return dist <= maxDist ? dist : -1;
    }

    private static int substringFuzzyDistance(String query, String candidate) {
        if (candidate.length() <= query.length() || candidate.length() > MAX_FUZZY_TOKEN_LENGTH) {
            return -1;
        }
        int maxDist = query.length() >= 6 ? 2 : 1;
        if (maxDist * 3 > query.length()) {
            maxDist = query.length() / 3;
        }
        if (maxDist <= 0) {
            return -1;
        }
        int minWin = Math.max(1, query.length() - maxDist);
        int maxWin = Math.min(candidate.length(), query.length() + maxDist);
        int best = Integer.MAX_VALUE;
        for (int len = minWin; len <= maxWin; len++) {
            for (int start = 0; start + len <= candidate.length(); start++) {
                int dist = damerauLevenshtein(query, candidate, start, len);
                if (dist < best) {
                    best = dist;
                    if (best == 0) {
                        return 0;
                    }
                }
            }
        }
        return best <= maxDist && best * 3 <= query.length() ? best : -1;
    }

    /** Optimal String Alignment (restricted Damerau-Levenshtein) distance. */
    public static int damerauLevenshtein(String a, String b) {
        return damerauLevenshtein(a, b, 0, b.length());
    }

    private static int damerauLevenshtein(String a, String b, int bOffset, int bLen) {
        int aLen = a.length();
        if (aLen == 0) return bLen;
        if (bLen == 0) return aLen;

        int[][] dp = new int[aLen + 1][bLen + 1];
        for (int i = 0; i <= aLen; i++) dp[i][0] = i;
        for (int j = 0; j <= bLen; j++) dp[0][j] = j;

        for (int i = 1; i <= aLen; i++) {
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= bLen; j++) {
                char cb = b.charAt(bOffset + j - 1);
                int cost = ca == cb ? 0 : 1;
                int best = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
                if (i > 1 && j > 1
                        && ca == b.charAt(bOffset + j - 2)
                        && a.charAt(i - 2) == cb) {
                    best = Math.min(best, dp[i - 2][j - 2] + 1);
                }
                dp[i][j] = best;
            }
        }
        return dp[aLen][bLen];
    }

    private static UUID tryParseUuid(String raw) {
        if (raw.length() != 36 || raw.charAt(8) != '-') {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
