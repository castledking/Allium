package codes.castled.curios;

/** The same level budget and merge rule apply to every curio definition. */
record Progression(int level, double xp) {
    Progression {
        if (level < 0 || !Double.isFinite(xp) || xp < 0) throw new IllegalArgumentException("invalid progression");
    }

    Progression award(double amount, int maxLevel, int step) {
        if (!Double.isFinite(amount) || amount < 0) throw new IllegalArgumentException("invalid XP");
        if (level >= maxLevel) return this;
        int next = level;
        double remainder = xp + amount;
        while (next < maxLevel && remainder >= cost(next, step)) {
            remainder -= cost(next, step);
            next++;
        }
        return new Progression(next, next == maxLevel ? 0 : remainder);
    }

    Progression merge(Progression donor, int maxLevel, int step) {
        double value = donor.xp;
        for (int i = 0; i < Math.min(donor.level, maxLevel); i++) value += cost(i, step);
        return award(value + step, maxLevel, step);
    }

    private static long cost(int level, int step) {
        return (level + 1L) * step;
    }
}
