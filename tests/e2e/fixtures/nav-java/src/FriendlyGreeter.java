public class FriendlyGreeter implements Greeter {
    @Override
    public String greet(String name) {
        return format(name);
    }

    private String format(String name) {
        return "Hi, " + name;
    }
}
