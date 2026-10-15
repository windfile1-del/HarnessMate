package javax.swing;

/**
 * Android linkage surface for Kotlin 1.9.20's IntelliJ-derived file types.
 * No Swing painting is supported: the compiler only uses Icon as a type in
 * reflective method signatures. Keep the standard interface descriptors so
 * method enumeration sees the same type as on the host JDK.
 */
public interface Icon {
    void paintIcon(java.awt.Component component, java.awt.Graphics graphics, int x, int y);

    int getIconWidth();

    int getIconHeight();
}
