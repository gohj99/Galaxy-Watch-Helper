package com.gohj99.galaxywatchhelper.utils.vpn;

import org.junit.Test;
import static org.junit.Assert.*;

public class VpnReflectionTest {
    // Like platform NetworkAgent: abstract, with a real constructor and no abstract methods.
    public abstract static class AbstractAgent {
        final String tag;
        AbstractAgent(String tag) { this.tag = tag; }
    }
    public static final class ConcreteAgent extends AbstractAgent {
        ConcreteAgent(String tag) { super(tag); }
    }
    public static final class ThrowingAgent {
        ThrowingAgent() { throw new SecurityException("constructor denied"); }
    }
    public static final class CheckedFailure {
        CheckedFailure() throws Exception { throw new Exception("checked failure"); }
    }

    @Test public void existingConstructorDoesNotMakeAnAbstractClassInstantiable() throws Exception {
        assertNotNull(AbstractAgent.class.getDeclaredConstructor(String.class));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> VpnReflection.constructor(AbstractAgent.class, String.class));
        assertTrue(error.getMessage().contains("abstract"));
        assertTrue(error.getMessage().contains(AbstractAgent.class.getName()));
    }

    @Test public void concreteSubclassExecutesSuperclassConstructor() {
        ConcreteAgent agent = (ConcreteAgent) VpnReflection.construct(ConcreteAgent.class,
                new Class<?>[] {String.class}, "GWHVpn");
        assertEquals("GWHVpn", agent.tag);
    }

    @Test public void constructorRuntimeFailureKeepsItsRealTypeAndMessage() {
        SecurityException error = assertThrows(SecurityException.class,
                () -> VpnReflection.construct(ThrowingAgent.class, new Class<?>[0]));
        assertEquals("constructor denied", error.getMessage());
    }

    @Test public void checkedConstructorFailureRetainsCause() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> VpnReflection.construct(CheckedFailure.class, new Class<?>[0]));
        assertEquals("checked failure", error.getCause().getMessage());
        assertTrue(error.getMessage().contains("checked failure"));
    }
}
