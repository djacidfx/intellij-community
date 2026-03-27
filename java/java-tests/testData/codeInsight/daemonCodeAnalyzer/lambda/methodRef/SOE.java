import java.util.*;

class LambdaTest {
    public void testR() {
        <error descr="Variable expected">new ArrayList<String>() :: size</error> = ""; 

    }
}