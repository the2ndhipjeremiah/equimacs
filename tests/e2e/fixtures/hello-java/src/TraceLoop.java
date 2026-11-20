public class TraceLoop {
    public static void main(String[] args) {
        int sum = 0;
        for (int i = 1; i <= 4; i++) {
            sum += i;
            System.out.println(i + ":" + sum);
        }
        System.out.println("done " + sum);
    }
}
