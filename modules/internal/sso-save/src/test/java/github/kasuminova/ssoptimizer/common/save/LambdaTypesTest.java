package github.kasuminova.ssoptimizer.common.save;

import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LambdaTypesTest {
    @Test
    void recognizesSerializableLambdaAndRejectsOrdinaryClasses() {
        final IntUnaryOperator adder = (IntUnaryOperator & Serializable) value -> value + 7;
        assertTrue(LambdaTypes.isLambda(adder.getClass()));
        assertFalse(LambdaTypes.isLambda(null));
        assertFalse(LambdaTypes.isLambda(String.class));
    }

    @Test
    void usesBinaryNameOnlyWhenSimpleNameIsEmpty() {
        assertTrue(LambdaTypes.matchesName("", "example.Owner$$Lambda/0x01234"));
        assertTrue(LambdaTypes.matchesName("Owner$$Lambda$4/0x01234", "ignored"));
        assertFalse(LambdaTypes.matchesName("Owner", "example.Owner$$Lambda/0x01234"));
        assertFalse(LambdaTypes.matchesName("", "example.Owner$Ordinary"));
    }
}
