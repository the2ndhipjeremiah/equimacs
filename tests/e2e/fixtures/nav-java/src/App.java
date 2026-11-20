public class App {
    public static void main(String[] args) {
        Greeter greeter = new FriendlyGreeter();
        System.out.println(greeter.greet("world"));
        helper(greeter);
    }

    static void helper(Greeter greeter) {
        System.out.println(greeter.greet("again"));
    }
}
