package juloo.keyboard2;

import java.util.ArrayList;
import org.junit.Test;
import static org.junit.Assert.*;

public class PointersTest
{
  @Test
  public void automatic_shift_is_not_a_system_modifier()
  {
    Pointers pointers = pointers();

    pointers.set_fake_pointer_state(KeyboardData.Key.EMPTY, KeyValue.SHIFT, true, false);

    assertTrue(pointers.getModifiers().has(KeyValue.Modifier.SHIFT));
    assertFalse(pointers.getModifiersWithoutFake().has(KeyValue.Modifier.SHIFT));
  }

  Pointers pointers()
  {
    Pointers pointers = allocate(Pointers.class);
    set_field(pointers, "_ptrs", new ArrayList());
    set_field(pointers, "_handler", new Handler());
    set_field(pointers, "_config", allocate(Config.class));
    return pointers;
  }

  @SuppressWarnings("unchecked")
  <T> T allocate(Class<T> type)
  {
    try
    {
      Class<?> unsafe_class = Class.forName("sun.misc.Unsafe");
      java.lang.reflect.Field field = unsafe_class.getDeclaredField("theUnsafe");
      field.setAccessible(true);
      Object unsafe = field.get(null);
      java.lang.reflect.Method allocate = unsafe_class.getMethod("allocateInstance", Class.class);
      return (T)allocate.invoke(unsafe, type);
    }
    catch (Exception e)
    {
      throw new AssertionError(e);
    }
  }

  void set_field(Object target, String name, Object value)
  {
    try
    {
      java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      field.set(target, value);
    }
    catch (Exception e)
    {
      throw new AssertionError(e);
    }
  }

  static class Handler implements Pointers.IPointerEventHandler
  {
    public KeyValue modifyKey(KeyValue key, Pointers.Modifiers modifiers) { return key; }
    public void onPointerDown(KeyValue key, boolean is_swipe) {}
    public void onPointerUp(KeyValue key, Pointers.Modifiers modifiers) {}
    public void onPointerFlagsChanged(boolean should_vibrate) {}
    public void onPointerHold(KeyValue key, Pointers.Modifiers modifiers) {}
  }
}
