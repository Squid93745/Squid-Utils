package dev.squidutils.tracker;

/**
 * One player-set timer - a plain mutable class rather than a record,
 * matching {@code AttributeDetector.State}'s own precedent for something
 * Gson round-trips directly with a bare {@code new Gson()}: needs a no-arg
 * constructor either way, and a record's interop with plain (not
 * MoulConfig-managed) Gson persistence is untested in this codebase, so
 * there is no reason to be the first thing to find out the hard way.
 */
public final class CustomTimer {

    public String id;
    public String name;
    public long endAtMillis;
    /** 0 means this timer does not repeat - fires once and is removed,
     *  same as before this field existed. */
    public long loopMillis;
    /** Only meaningful when {@link #loopMillis} is nonzero: total number of
     *  times this timer is meant to fire, or -1 for no limit. */
    public int loopQuantity;
    /** How many times this timer has fired so far - compared against
     *  {@link #loopQuantity} to know when a finite loop is done. */
    public int firedCount;

    /** Gson needs this for deserialization. */
    public CustomTimer() {}

    public CustomTimer(String id, String name, long endAtMillis) {
        this(id, name, endAtMillis, 0, 0);
    }

    public CustomTimer(String id, String name, long endAtMillis, long loopMillis, int loopQuantity) {
        this.id = id;
        this.name = name;
        this.endAtMillis = endAtMillis;
        this.loopMillis = loopMillis;
        this.loopQuantity = loopQuantity;
    }
}
