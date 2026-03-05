public class ReflectGenericStackInv {
  public static void main(String[] args) throws Exception {
    Class<?> c = Class.forName("appeng.helpers.externalstorage.GenericStackInv");
    System.out.println("Methods:");
    for (var m : c.getDeclaredMethods()) {
      System.out.println(m.toString());
    }
    System.out.println("Fields:");
    for (var f : c.getDeclaredFields()) {
      System.out.println(f.toString());
    }
  }
}
