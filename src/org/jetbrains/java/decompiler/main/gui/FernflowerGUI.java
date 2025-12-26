// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.java.decompiler.main.gui;

import org.jetbrains.java.decompiler.main.CancellationManager;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.Fernflower;
import org.jetbrains.java.decompiler.main.ClassesProcessor.ClassNode;
import org.jetbrains.java.decompiler.main.extern.IBytecodeProvider;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;
import org.jetbrains.java.decompiler.struct.IDecompiledData;
import org.jetbrains.java.decompiler.struct.StructClass;
import org.jetbrains.java.decompiler.struct.StructContext;
import org.jetbrains.java.decompiler.util.InterpreterUtil;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class FernflowerGUI extends JFrame {
  private static final Color KEYWORD_COLOR = new Color(127, 0, 85);
  private static final Color STRING_COLOR = new Color(42, 0, 255);
  private static final Color COMMENT_COLOR = new Color(63, 127, 95);
  private static final Color NUMBER_COLOR = new Color(0, 0, 255);

  private final JTree classTree;
  private final JTextPane editorPane;
  private final DefaultMutableTreeNode rootNode;
  private final JProgressBar progressBar;
  private final JLabel statusLabel;

  private Fernflower decompiler;
  private Map<String, StructClass> decompiledClasses = new HashMap<>();
  private Map<String, String> decompiledContents = new HashMap<>();
  private File currentJarFile;

  public FernflowerGUI() {
    setTitle("Fernflower Decompiler");
    setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    setSize(1200, 800);
    setLocationRelativeTo(null);

    JMenuBar menuBar = createMenuBar();
    setJMenuBar(menuBar);

    JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
    splitPane.setDividerLocation(350);

    rootNode = new DefaultMutableTreeNode("Root");
    classTree = new JTree(rootNode);
    classTree.addTreeSelectionListener(new TreeSelectionListener() {
      @Override
      public void valueChanged(TreeSelectionEvent e) {
        onTreeSelectionChanged(e);
      }
    });

    JScrollPane treeScrollPane = new JScrollPane(classTree);
    treeScrollPane.setBorder(new LineBorder(Color.GRAY));

    editorPane = new JTextPane();
    editorPane.setEditable(false);
    editorPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    editorPane.setContentType("text/java");

    JScrollPane editorScrollPane = new JScrollPane(editorPane);
    editorScrollPane.setBorder(new LineBorder(Color.GRAY));

    splitPane.setLeftComponent(treeScrollPane);
    splitPane.setRightComponent(editorScrollPane);

    JPanel statusPanel = new JPanel(new BorderLayout());
    statusPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

    progressBar = new JProgressBar();
    progressBar.setStringPainted(true);
    progressBar.setVisible(false);

    statusLabel = new JLabel("Ready");
    statusLabel.setBorder(new EmptyBorder(0, 0, 0, 10));

    statusPanel.add(progressBar, BorderLayout.CENTER);
    statusPanel.add(statusLabel, BorderLayout.EAST);

    add(splitPane, BorderLayout.CENTER);
    add(statusPanel, BorderLayout.SOUTH);
  }

  private JMenuBar createMenuBar() {
    JMenuBar menuBar = new JMenuBar();

    JMenu fileMenu = new JMenu("File");

    JMenuItem openItem = new JMenuItem("Open JAR...");
    openItem.addActionListener(this::openJarFile);
    fileMenu.add(openItem);

    fileMenu.addSeparator();

    JMenuItem saveFolderItem = new JMenuItem("Save to Folder...");
    saveFolderItem.addActionListener(this::saveToFolder);
    fileMenu.add(saveFolderItem);

    JMenuItem saveZipItem = new JMenuItem("Save to ZIP...");
    saveZipItem.addActionListener(this::saveToZip);
    fileMenu.add(saveZipItem);

    fileMenu.addSeparator();

    JMenuItem exitItem = new JMenuItem("Exit");
    exitItem.addActionListener(e -> System.exit(0));
    fileMenu.add(exitItem);

    menuBar.add(fileMenu);

    return menuBar;
  }

  private void openJarFile(ActionEvent e) {
    JFileChooser fileChooser = new JFileChooser();
    fileChooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
      @Override
      public boolean accept(File f) {
        return f.isDirectory() || f.getName().toLowerCase().endsWith(".jar");
      }

      @Override
      public String getDescription() {
        return "JAR Files (*.jar)";
      }
    });

    int result = fileChooser.showOpenDialog(this);
    if (result == JFileChooser.APPROVE_OPTION) {
      currentJarFile = fileChooser.getSelectedFile();
      decompileJar(currentJarFile);
    }
  }

  private void decompileJar(File jarFile) {
    setStatus("Decompiling " + jarFile.getName() + "...");
    progressBar.setIndeterminate(true);
    progressBar.setVisible(true);
    classTree.setEnabled(false);
    editorPane.setEnabled(false);

    SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
      @Override
      protected Void doInBackground() throws Exception {
        Map<String, Object> options = new HashMap<>();
        options.put("ind", "   ");
        options.put("din", "1");
        options.put("dgs", "1");
        options.put("hes", "1");
        options.put("hdc", "1");

        MemoryResultSaver saver = new MemoryResultSaver();
        IFernflowerLogger logger = new SwingLogger();
        Fernflower newDecompiler = new Fernflower(new FileBytecodeProvider(), saver, options, logger);

        newDecompiler.addSource(jarFile);
        newDecompiler.decompileContext();

        decompiler = newDecompiler;
        StructContext structContext = DecompilerContext.getStructContext();
        if (structContext != null) {
          decompiledClasses = new HashMap<>(structContext.getClasses());
        }
        buildClassTree(decompiledClasses);

        return null;
      }

      @Override
      protected void done() {
        try {
          get();
          setStatus("Ready");
          progressBar.setVisible(false);
          classTree.setEnabled(true);
          editorPane.setEnabled(true);
          classTree.updateUI();
        } catch (Exception e) {
          setStatus("Error: " + e.getMessage());
          progressBar.setVisible(false);
          classTree.setEnabled(true);
          editorPane.setEnabled(true);
          JOptionPane.showMessageDialog(FernflowerGUI.this,
            "Error decompiling JAR: " + e.getMessage(),
            "Error",
            JOptionPane.ERROR_MESSAGE);
        }
      }
    };

    worker.execute();
  }

  private void buildClassTree(Map<String, StructClass> classes) {
    rootNode.removeAllChildren();
    Map<String, DefaultMutableTreeNode> packageMap = new TreeMap<>();

    for (String className : classes.keySet()) {
      String[] parts = className.split("/");
      DefaultMutableTreeNode currentNode = rootNode;
      StringBuilder packageName = new StringBuilder();

      for (int i = 0; i < parts.length - 1; i++) {
        if (packageName.length() > 0) {
          packageName.append("/");
        }
        packageName.append(parts[i]);
        String packageKey = packageName.toString();

        DefaultMutableTreeNode packageNode = packageMap.get(packageKey);
        if (packageNode == null) {
          packageNode = new DefaultMutableTreeNode(parts[i]);
          currentNode.add(packageNode);
          packageMap.put(packageKey, packageNode);
        }
        currentNode = packageNode;
      }

      String simpleClassName = parts[parts.length - 1];
      DefaultMutableTreeNode classNode = new DefaultMutableTreeNode(new ClassItem(simpleClassName, className));
      currentNode.add(classNode);
    }

    ((DefaultTreeModel) classTree.getModel()).reload();
  }

  private void onTreeSelectionChanged(TreeSelectionEvent e) {
    DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) classTree.getLastSelectedPathComponent();
    if (selectedNode == null || selectedNode.getUserObject() instanceof String) {
      editorPane.setText("");
      return;
    }

    ClassItem classItem = (ClassItem) selectedNode.getUserObject();
    String content = decompiledContents.get(classItem.fullName);

    if (content != null) {
      editorPane.setText(content);
      highlightSyntax();
    } else {
      StructClass structClass = decompiledClasses.get(classItem.fullName);
      if (structClass != null) {
        content = decompiler.getClassContent(structClass);
        if (content != null) {
          editorPane.setText(content);
          highlightSyntax();
        } else {
          editorPane.setText("// Error decompiling class: " + classItem.fullName);
        }
      } else {
        editorPane.setText("// Class not found: " + classItem.fullName);
      }
    }
  }

  private void highlightSyntax() {
    StyledDocument doc = editorPane.getStyledDocument();
    StyleContext styleContext = new StyleContext();
    Style defaultStyle = styleContext.addStyle("Default", null);
    StyleConstants.setFontFamily(defaultStyle, Font.MONOSPACED);
    StyleConstants.setFontSize(defaultStyle, 12);

    Style keywordStyle = styleContext.addStyle("Keyword", defaultStyle);
    StyleConstants.setForeground(keywordStyle, KEYWORD_COLOR);
    StyleConstants.setBold(keywordStyle, true);

    Style stringStyle = styleContext.addStyle("String", defaultStyle);
    StyleConstants.setForeground(stringStyle, STRING_COLOR);

    Style commentStyle = styleContext.addStyle("Comment", defaultStyle);
    StyleConstants.setForeground(commentStyle, COMMENT_COLOR);
    StyleConstants.setItalic(commentStyle, true);

    Style numberStyle = styleContext.addStyle("Number", defaultStyle);
    StyleConstants.setForeground(numberStyle, NUMBER_COLOR);

    String[] keywords = {"abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
      "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally",
      "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long",
      "native", "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
      "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while",
      "true", "false", "null", "var"};

    Set<String> keywordSet = new HashSet<>(Arrays.asList(keywords));

    try {
      String text = editorPane.getText();
      doc.remove(0, doc.getLength());

      boolean inComment = false;
      boolean inMultiLineComment = false;
      boolean inString = false;
      char stringDelimiter = 0;

      int start = 0;
      for (int i = 0; i < text.length(); i++) {
        char c = text.charAt(i);
        char next = i < text.length() - 1 ? text.charAt(i + 1) : '\0';

        if (!inMultiLineComment && !inString && !inComment) {
          if ((c == '/' && next == '*')) {
            doc.insertString(doc.getLength(), text.substring(start, i), defaultStyle);
            start = i;
            inMultiLineComment = true;
            i++;
          } else if ((c == '/' && next == '/')) {
            doc.insertString(doc.getLength(), text.substring(start, i), defaultStyle);
            start = i;
            inComment = true;
            i++;
          } else if (c == '"' || c == '\'') {
            doc.insertString(doc.getLength(), text.substring(start, i), defaultStyle);
            start = i;
            inString = true;
            stringDelimiter = c;
          }
        } else if (inString) {
          if (c == stringDelimiter) {
            doc.insertString(doc.getLength(), text.substring(start, i + 1), stringStyle);
            start = i + 1;
            inString = false;
          }
        } else if (inComment) {
          if (c == '\n') {
            doc.insertString(doc.getLength(), text.substring(start, i), commentStyle);
            start = i;
            inComment = false;
          }
        } else if (inMultiLineComment) {
          if (c == '*' && next == '/') {
            doc.insertString(doc.getLength(), text.substring(start, i + 2), commentStyle);
            start = i + 2;
            inMultiLineComment = false;
            i++;
          }
        }
      }

      if (start < text.length()) {
        if (inComment || inMultiLineComment) {
          doc.insertString(doc.getLength(), text.substring(start), commentStyle);
        } else if (inString) {
          doc.insertString(doc.getLength(), text.substring(start), stringStyle);
        } else {
          doc.insertString(doc.getLength(), text.substring(start), defaultStyle);
        }
      }

      String remainingText = doc.getText(0, doc.getLength());
      int lastPos = 0;
      for (String keyword : keywords) {
        int index = 0;
        while ((index = remainingText.indexOf(keyword, index)) != -1) {
          char prev = index > 0 ? remainingText.charAt(index - 1) : ' ';
          char next = index + keyword.length() < remainingText.length() ? remainingText.charAt(index + keyword.length()) : ' ';

          if (!Character.isLetterOrDigit(prev) && prev != '_' &&
            !Character.isLetterOrDigit(next) && next != '_') {
            doc.setCharacterAttributes(index, keyword.length(), keywordStyle, true);
          }
          index++;
        }
      }
    } catch (BadLocationException e) {
      e.printStackTrace();
    }
  }

  private void saveToFolder(ActionEvent e) {
    if (decompiledClasses.isEmpty()) {
      JOptionPane.showMessageDialog(this, "No decompiled classes to save", "Warning", JOptionPane.WARNING_MESSAGE);
      return;
    }

    JFileChooser fileChooser = new JFileChooser();
    fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    fileChooser.setDialogTitle("Select Output Folder");

    int result = fileChooser.showSaveDialog(this);
    if (result == JFileChooser.APPROVE_OPTION) {
      File outputFolder = fileChooser.getSelectedFile();
      saveClassesToFolder(outputFolder);
    }
  }

  private void saveClassesToFolder(File outputFolder) {
    SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
      @Override
      protected Void doInBackground() throws Exception {
        final int[] count = {0};
        final int total = decompiledClasses.size();

        for (Map.Entry<String, StructClass> entry : decompiledClasses.entrySet()) {
          String className = entry.getKey();
          String content = decompiledContents.get(className);

          if (content == null) {
            StructClass structClass = entry.getValue();
            content = decompiler.getClassContent(structClass);
          }

          if (content != null) {
            String javaFileName = className.replace('/', File.separatorChar) + ".java";
            File javaFile = new File(outputFolder, javaFileName);
            javaFile.getParentFile().mkdirs();

            try (Writer out = new OutputStreamWriter(new FileOutputStream(javaFile), StandardCharsets.UTF_8)) {
              out.write(content);
            }
          }

          count[0]++;
          final int progress = (int) ((count[0] * 100.0) / total);
          SwingUtilities.invokeLater(() -> {
            progressBar.setValue(progress);
            setStatus("Saved " + count[0] + " of " + total + " classes");
          });
        }

        return null;
      }

      @Override
      protected void done() {
        try {
          get();
          setStatus("Saved all classes to " + outputFolder.getAbsolutePath());
          progressBar.setVisible(false);
          JOptionPane.showMessageDialog(FernflowerGUI.this,
            "All classes saved successfully!",
            "Success",
            JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
          setStatus("Error: " + e.getMessage());
          progressBar.setVisible(false);
          JOptionPane.showMessageDialog(FernflowerGUI.this,
            "Error saving classes: " + e.getMessage(),
            "Error",
            JOptionPane.ERROR_MESSAGE);
        }
      }
    };

    progressBar.setMaximum(100);
    progressBar.setValue(0);
    progressBar.setIndeterminate(false);
    progressBar.setVisible(true);
    setStatus("Saving classes to folder...");
    worker.execute();
  }

  private void saveToZip(ActionEvent e) {
    if (decompiledClasses.isEmpty()) {
      JOptionPane.showMessageDialog(this, "No decompiled classes to save", "Warning", JOptionPane.WARNING_MESSAGE);
      return;
    }

    JFileChooser fileChooser = new JFileChooser();
    fileChooser.setDialogTitle("Save as ZIP");
    fileChooser.setSelectedFile(new File(currentJarFile != null ? currentJarFile.getName().replace(".jar", "") + "_decompiled.zip" : "decompiled.zip"));

    int result = fileChooser.showSaveDialog(this);
    if (result == JFileChooser.APPROVE_OPTION) {
      File zipFile = fileChooser.getSelectedFile();
      saveClassesToZip(zipFile);
    }
  }

  private void saveClassesToZip(File zipFile) {
    SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
      @Override
      protected Void doInBackground() throws Exception {
        final int[] count = {0};
        final int total = decompiledClasses.size();

        try (ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(zipFile))) {
          for (Map.Entry<String, StructClass> entry : decompiledClasses.entrySet()) {
            String className = entry.getKey();
            String content = decompiledContents.get(className);

            if (content == null) {
              StructClass structClass = entry.getValue();
              content = decompiler.getClassContent(structClass);
            }

            if (content != null) {
              String entryName = className.replace('/', '/') + ".java";
              ZipEntry zipEntry = new ZipEntry(entryName);
              zipOut.putNextEntry(zipEntry);
              zipOut.write(content.getBytes(StandardCharsets.UTF_8));
              zipOut.closeEntry();
            }

            count[0]++;
            final int progress = (int) ((count[0] * 100.0) / total);
            SwingUtilities.invokeLater(() -> {
              progressBar.setValue(progress);
              setStatus("Saved " + count[0] + " of " + total + " classes");
            });
          }
        }

        return null;
      }

      @Override
      protected void done() {
        try {
          get();
          setStatus("Saved all classes to " + zipFile.getAbsolutePath());
          progressBar.setVisible(false);
          JOptionPane.showMessageDialog(FernflowerGUI.this,
            "All classes saved successfully!",
            "Success",
            JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
          setStatus("Error: " + e.getMessage());
          progressBar.setVisible(false);
          JOptionPane.showMessageDialog(FernflowerGUI.this,
            "Error saving ZIP: " + e.getMessage(),
            "Error",
            JOptionPane.ERROR_MESSAGE);
        }
      }
    };

    progressBar.setMaximum(100);
    progressBar.setValue(0);
    progressBar.setIndeterminate(false);
    progressBar.setVisible(true);
    setStatus("Saving classes to ZIP...");
    worker.execute();
  }

  private void setStatus(String status) {
    statusLabel.setText(status);
  }

  public static void main(String[] args) {
    SwingUtilities.invokeLater(() -> {
      try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
      } catch (Exception e) {
        e.printStackTrace();
      }

      FernflowerGUI gui = new FernflowerGUI();
      gui.setVisible(true);
    });
  }

  private static class ClassItem {
    final String name;
    final String fullName;

    ClassItem(String name, String fullName) {
      this.name = name;
      this.fullName = fullName;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  private static class FileBytecodeProvider implements IBytecodeProvider {
    @Override
    public byte[] getBytecode(String externalPath, String internalPath) throws IOException {
      File file = new File(externalPath);
      if (internalPath == null) {
        return InterpreterUtil.getBytes(file);
      } else {
        try (ZipFile archive = new ZipFile(file)) {
          ZipEntry entry = archive.getEntry(internalPath);
          if (entry == null) {
            throw new IOException("Entry not found: " + internalPath);
          }
          return InterpreterUtil.getBytes(archive, entry);
        }
      }
    }
  }

  private class MemoryResultSaver implements IResultSaver {
    @Override
    public void saveFolder(String path) {
    }

    @Override
    public void copyFile(String source, String path, String entryName) {
    }

    @Override
    public void saveClassFile(String path, String qualifiedName, String entryName, String content, int[] mapping) {
      String className = qualifiedName.replace('.', '/');
      decompiledContents.put(className, content);
    }

    @Override
    public void createArchive(String path, String archiveName, Manifest manifest) {
    }

    @Override
    public void saveDirEntry(String path, String archiveName, String entryName) {
    }

    @Override
    public void copyEntry(String source, String path, String archiveName, String entryName) {
    }

    @Override
    public void saveClassEntry(String path, String archiveName, String qualifiedName, String entryName, String content) {
      String className = qualifiedName.replace('.', '/');
      decompiledContents.put(className, content);
    }

    @Override
    public void closeArchive(String path, String archiveName) {
    }
  }

  private class SwingLogger extends IFernflowerLogger {
    @Override
    public void writeMessage(String message, Severity severity) {
      SwingUtilities.invokeLater(() -> {
        if (severity == Severity.ERROR || severity == Severity.WARN) {
          setStatus(message);
        }
      });
    }

    @Override
    public void writeMessage(String message, Severity severity, Throwable t) {
      SwingUtilities.invokeLater(() -> setStatus(message + ": " + t.getMessage()));
    }
  }
}
