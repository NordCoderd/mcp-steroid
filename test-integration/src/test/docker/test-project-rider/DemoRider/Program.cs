using DemoRider;

var players = new List<Player>
{
    new("Ada", 120),
    new("Linus", 450),
    new("Grace", 300)
};

var expected = new[] { "Linus", "Grace", "Ada" };
var actual = Leaderboard.GetLeaderboard(players).Select(p => p.Name).ToArray();

Console.WriteLine($"Expected: [{string.Join(", ", expected)}]");
Console.WriteLine($"Actual  : [{string.Join(", ", actual)}]");

if (!expected.SequenceEqual(actual))
{
    Console.WriteLine("ERROR: Leaderboard is wrong (intentionally)");
    Environment.Exit(1);
}
