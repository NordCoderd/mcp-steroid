using DemoRider;
using NUnit.Framework;

namespace DemoRider.Tests;

/// <summary>
/// Unit tests for <see cref="Leaderboard.GetLeaderboard"/>.
///
/// These tests are INTENTIONALLY FAILING due to a bug in GetLeaderboard().
/// The method calls players.OrderByDescending(p => p.Score) but ignores
/// the return value, so the original (unsorted) list is returned.
///
/// Use the debugger to discover why the assertion fails:
/// - Set a breakpoint inside GetLeaderboard()
/// - Run this test via "Debug" in the IDE test runner
/// - Observe that OrderByDescending() produces a sorted sequence but it is never assigned back
/// </summary>
[TestFixture]
public class LeaderboardTests
{
    [Test]
    public void GetLeaderboard_ReturnsSortedByScoreDescending()
    {
        var players = new List<Player>
        {
            new("Ada", 120),
            new("Linus", 450),
            new("Grace", 300),
        };

        var result = Leaderboard.GetLeaderboard(players);

        // Linus (450) should be first, Grace (300) second, Ada (120) third
        Assert.That(result[0].Name, Is.EqualTo("Linus"),
            $"Expected Linus first (score=450), got {result[0].Name} (score={result[0].Score})");
        Assert.That(result[1].Name, Is.EqualTo("Grace"),
            $"Expected Grace second (score=300), got {result[1].Name} (score={result[1].Score})");
        Assert.That(result[2].Name, Is.EqualTo("Ada"),
            $"Expected Ada third (score=120), got {result[2].Name} (score={result[2].Score})");
    }

    [Test]
    public void GetLeaderboard_ScoresAreInDescendingOrder()
    {
        var players = new List<Player>
        {
            new("X", 10),
            new("Y", 50),
            new("Z", 30),
        };

        var result = Leaderboard.GetLeaderboard(players);
        var scores = result.Select(p => p.Score).ToList();

        Assert.That(scores[0], Is.EqualTo(50), $"Highest score should be first, got: [{string.Join(", ", scores)}]");
        Assert.That(scores[1], Is.EqualTo(30), $"Second score should be 30, got: [{string.Join(", ", scores)}]");
        Assert.That(scores[2], Is.EqualTo(10), $"Lowest score should be last, got: [{string.Join(", ", scores)}]");
    }
}
