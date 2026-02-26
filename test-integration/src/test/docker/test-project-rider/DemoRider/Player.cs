namespace DemoRider;

public record Player(string Name, int Score);

public static class Leaderboard
{
    /// <summary>
    /// Returns a list of players sorted by score in descending order (highest first).
    /// </summary>
    public static List<Player> GetLeaderboard(List<Player> players)
    {
        // Intention: sort players by score descending (highest first)
        Console.WriteLine($"Sorting {players.Count} players by score...");
        players.OrderByDescending(p => p.Score);
        return players;
    }
}
