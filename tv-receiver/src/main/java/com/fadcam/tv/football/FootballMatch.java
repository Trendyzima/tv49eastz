package com.fadcam.tv.football;

/** Immutable football match row used by the native Live Scores UI. */
public final class FootballMatch {
    public final String id;
    public final String competition;
    public final String home;
    public final String away;
    public final String homeScore;
    public final String awayScore;
    public final String state;
    public final String kickoff;
    public final String status;

    public FootballMatch(String id, String competition, String home, String away,
                         String homeScore, String awayScore, String state,
                         String kickoff, String status) {
        this.id = id;
        this.competition = competition;
        this.home = home;
        this.away = away;
        this.homeScore = homeScore;
        this.awayScore = awayScore;
        this.state = state;
        this.kickoff = kickoff;
        this.status = status;
    }
}
