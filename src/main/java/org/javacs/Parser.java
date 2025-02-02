package org.javacs;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.lang.model.element.*;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import org.javacs.lsp.*;

class Parser {
    private static final JavaCompiler COMPILER = ServiceLoader.load(JavaCompiler.class).iterator().next();
    private static final SourceFileManager FILE_MANAGER = new SourceFileManager();

    /** Create a task that compiles a single file */
    private static JavacTask singleFileTask(JavaFileObject file) {
        return (JavacTask)
                COMPILER.getTask(null, FILE_MANAGER, Parser::ignoreError, List.of(), List.of(), List.of(file));
    }

    private final String contents;
    private final JavacTask task;
    private final CompilationUnitTree root;

    private Parser(JavaFileObject file) {
        Objects.requireNonNull(file);

        try {
            this.contents = file.getCharContent(false).toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.task = singleFileTask(file);
        try {
            this.root = task.parse().iterator().next();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static Parser parseFile(Path file) {
        return new Parser(new SourceFileObject(file));
    }

    static Parser parseJavaFileObject(JavaFileObject file) {
        return new Parser(file);
    }

    boolean showReferencesCodeLens(TreePath path) {
        return !isTestMethod(path)
                && !isTestClass(path)
                && !isCalledByTestFramework(path)
                && !isOverride(path)
                && !isMainMethod(path);
    }

    boolean isTestMethod(TreePath path) {
        var leaf = path.getLeaf();
        if (!(leaf instanceof MethodTree)) return false;
        var method = (MethodTree) leaf;
        for (var ann : method.getModifiers().getAnnotations()) {
            var type = ann.getAnnotationType();
            if (type instanceof IdentifierTree) {
                var id = (IdentifierTree) type;
                var name = id.getName();
                if (name.contentEquals("Test") || name.contentEquals("org.junit.Test")) {
                    return true;
                }
            }
        }
        return false;
    }

    boolean isCalledByTestFramework(TreePath path) {
        var leaf = path.getLeaf();
        if (!(leaf instanceof MethodTree)) return false;
        var method = (MethodTree) leaf;
        for (var ann : method.getModifiers().getAnnotations()) {
            var type = ann.getAnnotationType();
            if (type instanceof IdentifierTree) {
                var id = (IdentifierTree) type;
                var name = id.getName();
                if (name.contentEquals("Before") || name.contentEquals("org.junit.Before")) {
                    return true;
                }
            }
        }
        return false;
    }

    boolean isMainMethod(TreePath path) {
        var leaf = path.getLeaf();
        if (!(leaf instanceof MethodTree)) return false;
        var method = (MethodTree) leaf;
        var signature =
                method.getModifiers()
                        + ""
                        + method.getReturnType()
                        + " "
                        + method.getName()
                        + "("
                        + method.getParameters()
                        + ")";
        return signature.matches("public static void main\\(String\\[\\] .+\\)");
    }

    boolean isTestClass(TreePath path) {
        var leaf = path.getLeaf();
        if (!(leaf instanceof ClassTree)) return false;
        var cls = (ClassTree) leaf;
        for (var m : cls.getMembers()) {
            if (isTestMethod(new TreePath(path, m))) return true;
        }
        return false;
    }

    boolean isOverride(TreePath path) {
        var leaf = path.getLeaf();
        if (!(leaf instanceof MethodTree)) return false;
        var method = (MethodTree) leaf;
        for (var ann : method.getModifiers().getAnnotations()) {
            var type = ann.getAnnotationType();
            if (type instanceof IdentifierTree) {
                var id = (IdentifierTree) type;
                var name = id.getName();
                if (name.contentEquals("Override")) {
                    return true;
                }
            }
        }
        return false;
    }

    List<TreePath> codeLensDeclarations() {
        var found = new ArrayList<TreePath>();
        class FindDeclarations extends TreePathScanner<Void, Void> {
            boolean isClass(Tree t) {
                if (!(t instanceof ClassTree)) return false;
                var cls = (ClassTree) t;
                return cls.getKind() == Tree.Kind.CLASS;
            }

            boolean isPrivate(ModifiersTree t) {
                return t.getFlags().contains(Modifier.PRIVATE);
            }

            boolean isStatic(ModifiersTree t) {
                return t.getFlags().contains(Modifier.STATIC);
            }

            @Override
            public Void visitClass(ClassTree t, Void __) {
                if (isPrivate(t.getModifiers())) return null;
                found.add(getCurrentPath());
                return super.visitClass(t, null);
            }

            @Override
            public Void visitMethod(MethodTree t, Void __) {
                if (isPrivate(t.getModifiers())) return null;
                var path = getCurrentPath();
                found.add(path);
                return null;
            }

            @Override
            public Void visitVariable(VariableTree t, Void __) {
                if (isPrivate(t.getModifiers())) return null;
                if (!isStatic(t.getModifiers())) return null;
                var path = getCurrentPath();
                var parent = path.getParentPath().getLeaf();
                if (isClass(parent)) {
                    found.add(path);
                }
                return null;
            }
        }
        new FindDeclarations().scan(root, null);

        return found;
    }

    Set<Name> packagePrivateClasses() {
        var result = new HashSet<Name>();
        for (var t : root.getTypeDecls()) {
            if (t instanceof ClassTree) {
                var c = (ClassTree) t;
                var isPublic = c.getModifiers().getFlags().contains(Modifier.PUBLIC);
                if (!isPublic) {
                    result.add(c.getSimpleName());
                }
            }
        }
        return result;
    }

    Optional<Range> range(TreePath path) {
        return range(task, contents, path);
    }

    Optional<Location> location(TreePath path) {
        var uri = root.getSourceFile().toUri();
        return range(task, contents, path).map(range -> new Location(uri, range));
    }

    /** Find the smallest tree that includes the cursor */
    TreePath findPath(long cursor) {
        var finder = new FindSmallest(cursor, task, root);
        finder.scan(root, null);
        if (finder.found == null) {
            return new TreePath(root);
        }
        return finder.found;
    }

    boolean isIdentifier(long cursor) {
        var path = findPath(cursor);
        return path.getLeaf() instanceof IdentifierTree;
    }

    static boolean inClass(TreePath path) {
        for (var part : path) {
            if (part instanceof ClassTree) {
                return true;
            }
        }
        return false;
    }

    static boolean inMethod(TreePath path) {
        for (var part : path) {
            if (part instanceof MethodTree) {
                return true;
            }
        }
        return false;
    }

    List<FoldingRange> foldingRanges() {
        var imports = new ArrayList<TreePath>();
        var blocks = new ArrayList<TreePath>();
        // TODO find comment trees
        var comments = new ArrayList<TreePath>();
        class FindFoldingRanges extends TreePathScanner<Void, Void> {
            @Override
            public Void visitClass(ClassTree t, Void __) {
                blocks.add(getCurrentPath());
                return super.visitClass(t, null);
            }

            @Override
            public Void visitBlock(BlockTree t, Void __) {
                blocks.add(getCurrentPath());
                return super.visitBlock(t, null);
            }

            @Override
            public Void visitImport(ImportTree t, Void __) {
                imports.add(getCurrentPath());
                return null;
            }
        }
        new FindFoldingRanges().scan(root, null);

        var all = new ArrayList<FoldingRange>();

        // Merge import ranges
        if (!imports.isEmpty()) {
            var merged = asFoldingRange(imports.get(0), FoldingRangeKind.Imports);
            for (var i : imports) {
                var r = asFoldingRange(i, FoldingRangeKind.Imports);
                if (r.startLine <= merged.endLine + 1) {
                    merged =
                            new FoldingRange(
                                    merged.startLine,
                                    merged.startCharacter,
                                    r.endLine,
                                    r.endCharacter,
                                    FoldingRangeKind.Imports);
                } else {
                    all.add(merged);
                    merged = r;
                }
            }
            all.add(merged);
        }

        // Convert blocks and comments
        for (var t : blocks) {
            all.add(asFoldingRange(t, FoldingRangeKind.Region));
        }
        for (var t : comments) {
            all.add(asFoldingRange(t, FoldingRangeKind.Region));
        }

        return all;
    }

    private FoldingRange asFoldingRange(TreePath t, String kind) {
        var trees = Trees.instance(task);
        var pos = trees.getSourcePositions();
        var lines = t.getCompilationUnit().getLineMap();
        var start = (int) pos.getStartPosition(t.getCompilationUnit(), t.getLeaf());
        var end = (int) pos.getEndPosition(t.getCompilationUnit(), t.getLeaf());

        // If this is a class tree, adjust start position to '{'
        if (t.getLeaf() instanceof ClassTree) {
            CharSequence content;
            try {
                content = t.getCompilationUnit().getSourceFile().getCharContent(true);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            for (var i = start; i < content.length(); i++) {
                if (content.charAt(i) == '{') {
                    start = i;
                    break;
                }
            }
        }

        // Convert offset to 0-based line and character
        var startLine = (int) lines.getLineNumber(start) - 1; // TODO (int) is not coloring
        var startChar = (int) lines.getColumnNumber(start) - 1;
        var endLine = (int) lines.getLineNumber(end) - 1;
        var endChar = (int) lines.getColumnNumber(end) - 1;

        // If this is a block, move end position back one line so we don't fold the '}'
        if (t.getLeaf() instanceof ClassTree || t.getLeaf() instanceof BlockTree) {
            endLine--;
        }

        return new FoldingRange(startLine, startChar, endLine, endChar, kind);
    }

    /** Find and source code associated with a ptr */
    Optional<TreePath> fuzzyFind(Ptr ptr) {
        LOG.info(
                String.format(
                        "...find fuzzy match of %s in %s ...",
                        ptr, StringSearch.fileName(root.getSourceFile().toUri())));

        class FindPtr extends TreePathScanner<Void, Void> {
            int bestMatch = Ptr.NOT_MATCHED;
            TreePath found;

            void check() {
                var path = getCurrentPath();
                var mismatch = ptr.fuzzyMatch(path);
                if (mismatch < bestMatch) {
                    found = path;
                    bestMatch = mismatch;
                }
            }

            @Override
            public Void visitClass(ClassTree node, Void aVoid) {
                check();
                return super.visitClass(node, aVoid);
            }

            @Override
            public Void visitMethod(MethodTree node, Void aVoid) {
                check();
                // Ptr can't point inside a method
                return null;
            }

            @Override
            public Void visitVariable(VariableTree node, Void aVoid) {
                check();
                // Ptr can't point inside a field
                return null;
            }
        }
        var find = new FindPtr();
        find.scan(root, null);
        if (find.found != null)
            LOG.info(
                    String.format(
                            "...`%s` with score %d is best match", describeTree(find.found.getLeaf()), find.bestMatch));
        else LOG.info("...no match found");
        return Optional.ofNullable(find.found);
    }

    DocCommentTree doc(TreePath path) {
        // Find ptr in the file
        // Find the documentation attached to el
        var docs = DocTrees.instance(task);
        var doc = docs.getDocCommentTree(path);
        if (doc == null) return EMPTY_DOC;
        return doc;
    }

    static Optional<Range> range(JavacTask task, String contents, TreePath path) {
        // Find start position
        var trees = Trees.instance(task);
        var pos = trees.getSourcePositions();
        var root = path.getCompilationUnit();
        var lines = root.getLineMap();
        var start = (int) pos.getStartPosition(root, path.getLeaf());
        var end = (int) pos.getEndPosition(root, path.getLeaf());

        // If start is -1, give up
        if (start == -1) {
            LOG.warning(String.format("Couldn't locate `%s`", path.getLeaf()));
            return Optional.empty();
        }
        // If end is bad, guess based on start
        if (end == -1) {
            end = start + path.getLeaf().toString().length();
        }

        if (path.getLeaf() instanceof ClassTree) {
            var cls = (ClassTree) path.getLeaf();

            // If class has annotations, skip over them
            if (!cls.getModifiers().getAnnotations().isEmpty())
                start = (int) pos.getEndPosition(root, cls.getModifiers());

            // Find position of class name
            var name = cls.getSimpleName().toString();
            start = indexOf(contents, name, start);
            if (start == -1) {
                LOG.warning(String.format("Couldn't find identifier `%s` in `%s`", name, path.getLeaf()));
                return Optional.empty();
            }
            end = start + name.length();
        }
        if (path.getLeaf() instanceof MethodTree) {
            var method = (MethodTree) path.getLeaf();

            // If method has annotations, skip over them
            if (!method.getModifiers().getAnnotations().isEmpty())
                start = (int) pos.getEndPosition(root, method.getModifiers());

            // Find position of method name
            var name = method.getName().toString();
            if (name.equals("<init>")) {
                name = className(path);
            }
            start = indexOf(contents, name, start);
            if (start == -1) {
                LOG.warning(String.format("Couldn't find identifier `%s` in `%s`", name, path.getLeaf()));
                return Optional.empty();
            }
            end = start + name.length();
        }
        if (path.getLeaf() instanceof VariableTree) {
            var field = (VariableTree) path.getLeaf();

            // If field has annotations, skip over them
            if (!field.getModifiers().getAnnotations().isEmpty())
                start = (int) pos.getEndPosition(root, field.getModifiers());

            // Find position of method name
            var name = field.getName().toString();
            start = indexOf(contents, name, start);
            if (start == -1) {
                LOG.warning(String.format("Couldn't find identifier `%s` in `%s`", name, path.getLeaf()));
                return Optional.empty();
            }
            end = start + name.length();
        }
        if (path.getLeaf() instanceof MemberSelectTree) {
            var member = (MemberSelectTree) path.getLeaf();
            var name = member.getIdentifier().toString();
            start = indexOf(contents, name, start);
            if (start == -1) {
                LOG.warning(String.format("Couldn't find identifier `%s` in `%s`", name, path.getLeaf()));
                return Optional.empty();
            }
            end = start + name.length();
        }
        var startLine = (int) lines.getLineNumber(start);
        var startCol = (int) lines.getColumnNumber(start);
        var endLine = (int) lines.getLineNumber(end);
        var endCol = (int) lines.getColumnNumber(end);
        var range = new Range(new Position(startLine - 1, startCol - 1), new Position(endLine - 1, endCol - 1));

        return Optional.of(range);
    }

    private static int indexOf(String contents, String name, int start) {
        var matcher = Pattern.compile("\\b" + name + "\\b").matcher(contents);
        if (matcher.find(start)) {
            return matcher.start();
        }
        return -1;
    }

    List<SymbolInformation> documentSymbols() {
        return findSymbolsMatching("");
    }

    List<SymbolInformation> findSymbolsMatching(String query) {
        List<TreePath> found = new ArrayList<>();
        class Find extends TreePathScanner<Void, Void> {
            void accept(TreePath path) {
                var node = path.getLeaf();
                if (node instanceof ClassTree) {
                    var c = (ClassTree) node;
                    if (StringSearch.matchesTitleCase(c.getSimpleName(), query)) found.add(path);
                } else if (node instanceof MethodTree) {
                    var m = (MethodTree) node;
                    if (StringSearch.matchesTitleCase(m.getName(), query)) found.add(path);
                } else if (node instanceof VariableTree) {
                    var v = (VariableTree) node;
                    if (StringSearch.matchesTitleCase(v.getName(), query)) found.add(path);
                }
            }

            @Override
            public Void visitClass(ClassTree node, Void nothing) {
                super.visitClass(node, nothing);
                accept(getCurrentPath());
                for (var t : node.getMembers()) {
                    var child = new TreePath(getCurrentPath(), t);
                    accept(child);
                }
                return null;
            }

            void run() {
                scan(root, null);
            }
        }
        new Find().run();
        var symbols = new ArrayList<SymbolInformation>();
        for (var path : found) {
            asSymbolInformation(path, symbols);
        }
        return symbols;
    }

    private void asSymbolInformation(TreePath path, List<SymbolInformation> acc) {
        var l = location(path);
        if (l.isEmpty()) return;
        var i = new SymbolInformation();
        var t = path.getLeaf();
        i.kind = asSymbolKind(t.getKind());
        i.name = symbolName(t);
        i.containerName = containerName(path);
        i.location = l.get();
        acc.add(i);
    }

    private static Integer asSymbolKind(Tree.Kind k) {
        switch (k) {
            case ANNOTATION_TYPE:
            case CLASS:
                return SymbolKind.Class;
            case ENUM:
                return SymbolKind.Enum;
            case INTERFACE:
                return SymbolKind.Interface;
            case METHOD:
                return SymbolKind.Method;
            case TYPE_PARAMETER:
                return SymbolKind.TypeParameter;
            case VARIABLE:
                // This method is used for symbol-search functionality,
                // where we only return fields, not local variables
                return SymbolKind.Field;
            default:
                return null;
        }
    }

    private static String containerName(TreePath path) {
        var parent = path.getParentPath();
        while (parent != null) {
            var t = parent.getLeaf();
            if (t instanceof ClassTree) {
                var c = (ClassTree) t;
                return c.getSimpleName().toString();
            } else if (t instanceof CompilationUnitTree) {
                var c = (CompilationUnitTree) t;
                return Objects.toString(c.getPackageName(), "");
            } else {
                parent = parent.getParentPath();
            }
        }
        return null;
    }

    private static String symbolName(Tree t) {
        if (t instanceof ClassTree) {
            var c = (ClassTree) t;
            return c.getSimpleName().toString();
        } else if (t instanceof MethodTree) {
            var m = (MethodTree) t;
            return m.getName().toString();
        } else if (t instanceof VariableTree) {
            var v = (VariableTree) t;
            return v.getName().toString();
        } else {
            LOG.warning("Don't know how to create SymbolInformation from " + t);
            return "???";
        }
    }

    private static final DocCommentTree EMPTY_DOC = makeEmptyDoc();

    private static DocCommentTree makeEmptyDoc() {
        var file = new SourceFileObject(Paths.get("/Foo.java"), "/** */ class Foo { }", Instant.now());
        var task =
                (JavacTask)
                        COMPILER.getTask(
                                null,
                                FILE_MANAGER,
                                Parser::ignoreError,
                                List.of(),
                                null,
                                Collections.singletonList(file));
        var docs = DocTrees.instance(task);
        CompilationUnitTree root;
        try {
            root = task.parse().iterator().next();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        class FindEmptyDoc extends TreePathScanner<Void, Void> {
            DocCommentTree found;

            @Override
            public Void visitClass(ClassTree t, Void __) {
                found = docs.getDocCommentTree(getCurrentPath());
                return null;
            }
        }
        var find = new FindEmptyDoc();
        find.scan(root, null);
        return Objects.requireNonNull(find.found);
    }

    private static void ignoreError(javax.tools.Diagnostic<? extends JavaFileObject> err) {
        // Too noisy, this only comes up in parse tasks which tend to be less important
        // LOG.warning(err.getMessage(Locale.getDefault()));
    }

    static String describeTree(Tree leaf) {
        if (leaf instanceof MethodTree) {
            var method = (MethodTree) leaf;
            var params = new StringJoiner(", ");
            for (var p : method.getParameters()) {
                params.add(p.getType() + " " + p.getName());
            }
            return method.getName() + "(" + params + ")";
        }
        if (leaf instanceof ClassTree) {
            var cls = (ClassTree) leaf;
            return "class " + cls.getSimpleName();
        }
        if (leaf instanceof BlockTree) {
            var block = (BlockTree) leaf;
            return String.format("{ ...%d lines... }", block.getStatements().size());
        }
        return leaf.toString();
    }

    List<String> accessibleClasses(String partialName, String fromPackage) {
        var toPackage = Objects.toString(root.getPackageName(), "");
        var samePackage = fromPackage.equals(toPackage) || toPackage.isEmpty();
        var result = new ArrayList<String>();
        for (var t : root.getTypeDecls()) {
            if (!(t instanceof ClassTree)) continue;
            var cls = (ClassTree) t;
            // If class is not accessible, skip it
            var isPublic = cls.getModifiers().getFlags().contains(Modifier.PUBLIC);
            if (!samePackage && !isPublic) continue;
            // If class doesn't match partialName, skip it
            var name = cls.getSimpleName().toString();
            if (!StringSearch.matchesPartialName(name, partialName)) continue;
            if (root.getPackageName() != null) {
                name = root.getPackageName() + "." + name;
            }
            result.add(name);
        }
        return result;
    }

    boolean mightContainDefinition(Element to) {
        var findName = simpleName(to);
        class Found extends RuntimeException {}
        class FindMethod extends TreePathScanner<Void, Void> {
            private Name className;

            @Override
            public Void visitClass(ClassTree t, Void __) {
                var prev = className;
                className = t.getSimpleName();
                super.visitClass(t, null);
                className = prev;
                return null;
            }

            @Override
            public Void visitMethod(MethodTree t, Void __) {
                var match =
                        t.getName().contentEquals(findName)
                                || t.getName().contentEquals("<init>") && className.contentEquals(findName);
                if (match) {
                    throw new Found();
                }
                return super.visitMethod(t, null);
            }
        }
        try {
            new FindMethod().scan(root, null);
        } catch (Found __) {
            return true;
        }
        return false;
    }

    static String simpleName(Element e) {
        if (e.getSimpleName().contentEquals("<init>")) {
            return e.getEnclosingElement().getSimpleName().toString();
        }
        return e.getSimpleName().toString();
    }

    private static String prune(
            CompilationUnitTree root,
            SourcePositions pos,
            StringBuilder buffer,
            long[] offsets,
            boolean eraseAfterCursor) {
        class Scan extends TreeScanner<Void, Void> {
            boolean erasedAfterCursor = !eraseAfterCursor;

            boolean containsCursor(Tree node) {
                var start = pos.getStartPosition(root, node);
                var end = pos.getEndPosition(root, node);
                for (var cursor : offsets) {
                    if (start <= cursor && cursor <= end) {
                        return true;
                    }
                }
                return false;
            }

            boolean anyContainsCursor(Collection<? extends Tree> nodes) {
                for (var n : nodes) {
                    if (containsCursor(n)) return true;
                }
                return false;
            }

            long lastCursorIn(Tree node) {
                var start = pos.getStartPosition(root, node);
                var end = pos.getEndPosition(root, node);
                long last = -1;
                for (var cursor : offsets) {
                    if (start <= cursor && cursor <= end) {
                        last = cursor;
                    }
                }
                if (last == -1) {
                    throw new RuntimeException(
                            String.format("No cursor in %s is between %d and %d", offsets, start, end));
                }
                return last;
            }

            @Override
            public Void visitImport(ImportTree node, Void __) {
                // Erase 'static' keyword so autocomplete works better
                if (containsCursor(node) && node.isStatic()) {
                    var start = (int) pos.getStartPosition(root, node);
                    start = buffer.indexOf("static", start);
                    var end = start + "static".length();
                    erase(buffer, start, end);
                }
                return super.visitImport(node, null);
            }

            @Override
            public Void visitSwitch(SwitchTree node, Void __) {
                if (containsCursor(node)) {
                    // Prevent the enclosing block from erasing the closing } of the switch
                    erasedAfterCursor = true;
                }
                return super.visitSwitch(node, null);
            }

            @Override
            public Void visitBlock(BlockTree node, Void __) {
                if (containsCursor(node)) {
                    super.visitBlock(node, null);
                    // When we find the deepest block that includes the cursor
                    if (!erasedAfterCursor) {
                        var cursor = lastCursorIn(node);
                        var start = cursor;
                        var end = pos.getEndPosition(root, node);
                        if (end >= buffer.length()) end = buffer.length() - 1;
                        // Find the next line
                        while (start < end && buffer.charAt((int) start) != '\n') start++;
                        // Find the end of the block
                        while (end > start && buffer.charAt((int) end) != '}') end--;
                        // Erase from next line to end of block
                        erase(buffer, start, end - 1);
                        erasedAfterCursor = true;
                    }
                } else if (!node.getStatements().isEmpty()) {
                    var first = node.getStatements().get(0);
                    var last = node.getStatements().get(node.getStatements().size() - 1);
                    var start = pos.getStartPosition(root, first);
                    var end = pos.getEndPosition(root, last);
                    if (end >= buffer.length()) end = buffer.length() - 1;
                    erase(buffer, start, end);
                }
                return null;
            }

            @Override
            public Void visitErroneous(ErroneousTree node, Void nothing) {
                return super.scan(node.getErrorTrees(), nothing);
            }
        }

        new Scan().scan(root, null);

        var pruned = buffer.toString();
        // For debugging:
        if (false) {
            var file = Paths.get(root.getSourceFile().toUri());
            var out = file.resolveSibling(file.getFileName() + ".pruned");
            try {
                Files.writeString(out, pruned);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return pruned;
    }

    private static void erase(StringBuilder buffer, long start, long end) {
        for (int i = (int) start; i < end; i++) {
            switch (buffer.charAt(i)) {
                case '\r':
                case '\n':
                    break;
                default:
                    buffer.setCharAt(i, ' ');
            }
        }
    }

    String prune(int line, int character) {
        var cursor = root.getLineMap().getPosition(line, character);
        return prune(cursor);
    }

    String prune(long cursor) {
        var pos = Trees.instance(task).getSourcePositions();
        var buffer = new StringBuilder(contents);
        return prune(root, pos, buffer, new long[] {cursor}, true);
    }

    String eraseCase(long cursor) {
        var path = findPath(cursor);
        while (path != null && path.getLeaf().getKind() != Tree.Kind.CASE) {
            path = path.getParentPath();
        }
        if (path == null) {
            LOG.warning("Found no case around " + path.getLeaf());
            return contents;
        }
        var caseTree = (CaseTree) path.getLeaf();
        var pos = Trees.instance(task).getSourcePositions();
        var start = pos.getStartPosition(root, caseTree);
        var buffer = new StringBuilder(contents);
        erase(buffer, start, cursor);
        return buffer.toString();
    }

    String prune(String name) {
        // Find all occurrences of name in contents
        var file = Paths.get(root.getSourceFile().toUri());
        var contents = FileStore.contents(file);
        var list = new ArrayList<Long>();
        var pattern = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");
        var matcher = pattern.matcher(contents);
        while (matcher.find()) {
            list.add((long) matcher.start());
        }
        var offsets = new long[list.size()];
        for (var i = 0; i < list.size(); i++) {
            offsets[i] = list.get(i);
        }
        // Erase all blocks that don't contain name
        var buffer = new StringBuilder(contents);
        var pos = Trees.instance(task).getSourcePositions();
        return prune(root, pos, buffer, offsets, false);
    }

    boolean insideClass(long cursor) {
        var path = findPath(cursor);
        while (path != null) {
            if (path.getLeaf() instanceof ClassTree) {
                return true;
            }
            path = path.getParentPath();
        }
        return false;
    }

    static Set<Path> potentialDefinitions(Element to) {
        LOG.info(String.format("Find potential definitions of `%s`...", to));

        // If `to` is private, any definitions must be in the same file
        if (to.getModifiers().contains(Modifier.PRIVATE)) {
            LOG.info(String.format("...`%s` is private", to));
            var set = new HashSet<Path>();
            declaringFile(to).ifPresent(set::add);
            return set;
        }

        if (to instanceof ExecutableElement) {
            var allFiles = FileStore.all();
            // Check if the file contains the name of `to`
            var hasWord = containsWord(allFiles, simpleName(to));
            // Parse each file and check if the syntax tree is consistent with a definition of `to`
            // This produces some false positives, but parsing is much faster than compiling,
            // so it's an effective optimization
            var matches = new HashSet<Path>();
            for (var file : hasWord) {
                if (parseFile(file).mightContainDefinition(to)) {
                    matches.add(file);
                }
            }
            var findName = simpleName(to);
            LOG.info(String.format("...%d files contain method `%s`", matches.size(), findName));
            return matches;
        } else {
            var files = new HashSet<Path>();
            declaringFile(to).ifPresent(files::add);
            return files;
        }
    }

    static Set<Path> potentialReferences(Path file, String name, boolean isType, Set<Modifier> flags) {
        LOG.info(String.format("...find potential references to `%s`...", name));
        var pkg = FileStore.packageName(file);

        // If `to` is private, any definitions must be in the same file
        if (flags.contains(Modifier.PRIVATE)) {
            LOG.info(String.format("...`%s` is private", name));
            return Set.of(file);
        }

        LOG.info(String.format("...find identifiers named `%s`", name));
        Collection<Path> allFiles;
        var isPackagePrivate = !flags.contains(Modifier.PUBLIC) && !flags.contains(Modifier.PROTECTED);
        if (isPackagePrivate) {
            // If `to` is package-private, only look in my own package
            allFiles = FileStore.list(pkg);
            LOG.info(String.format("...check %d files in my own package %s", allFiles.size(), pkg));
        } else {
            // Otherwise search all files
            allFiles = FileStore.all();
            LOG.info(String.format("...check %d files", allFiles.size()));
        }
        // Check if the file contains the name of `to`
        var hasWord = containsWord(allFiles, name);
        // You can't reference a TypeElement without importing it
        if (isType) {
            hasWord = containsImport(hasWord, pkg, name);
        }
        // Convert Path to URI
        var matches = new HashSet<Path>();
        for (var f : hasWord) {
            matches.add(f);
        }
        return matches;
    }

    private static Optional<Path> declaringFile(Element e) {
        // Find top-level type surrounding `to`
        LOG.info(String.format("...looking up declaring file of `%s`...", e));
        var top = topLevelDeclaration(e);
        if (!top.isPresent()) {
            LOG.warning("...no top-level type!");
            return Optional.empty();
        }
        // Find file by looking at package and class name
        LOG.info(String.format("...top-level type is %s", top.get()));
        var file = findDeclaringFile(top.get());
        if (!file.isPresent()) {
            LOG.info(String.format("...couldn't find declaring file for type"));
            return Optional.empty();
        }
        return file;
    }

    private static Optional<TypeElement> topLevelDeclaration(Element e) {
        if (e == null) return Optional.empty();
        var parent = e;
        TypeElement result = null;
        while (parent.getEnclosingElement() != null) {
            if (parent instanceof TypeElement) result = (TypeElement) parent;
            parent = parent.getEnclosingElement();
        }
        return Optional.ofNullable(result);
    }

    /** Find the file `e` was declared in */
    private static Optional<Path> findDeclaringFile(TypeElement e) {
        var name = e.getQualifiedName().toString();
        return FileStore.findDeclaringFile(name);
    }

    private static Cache<String, Boolean> cacheContainsWord = new Cache<>();

    private static List<Path> containsWord(Collection<Path> allFiles, String name) {
        // Figure out all files that need to be re-scanned
        var outOfDate = new ArrayList<Path>();
        for (var file : allFiles) {
            // If we know file doesn't contain a prefix of word, we know it doesn't contain the word
            var prefix = name.substring(0, name.length() - 1);
            if (!prefix.isEmpty() && cacheContainsWord.has(file, prefix) && !cacheContainsWord.get(file, prefix)) {
                cacheContainsWord.load(file, name, false);
                continue;
            }
            // Otherwise, scan the file in the next loop
            if (cacheContainsWord.needs(file, name)) {
                outOfDate.add(file);
            }
        }

        // Update those files in cacheContainsWord
        LOG.info(String.format("...scanning %d out-of-date files for the word `%s`", outOfDate.size(), name));
        for (var file : outOfDate) {
            var found = StringSearch.containsWord(file, name);
            cacheContainsWord.load(file, name, found);
        }

        // Assemble list of all files that contain name
        var hasWord = new ArrayList<Path>();
        for (var file : allFiles) {
            if (cacheContainsWord.get(file, name)) {
                hasWord.add(file);
            }
        }
        LOG.info(String.format("...%d files contain the word `%s`", hasWord.size(), name));

        return hasWord;
    }

    private static Cache<String, Boolean> cacheContainsImport = new Cache<>();

    private static List<Path> containsImport(Collection<Path> allFiles, String toPackage, String toClass) {
        // Figure out which files import `to`, explicitly or implicitly
        var qName = toPackage.isEmpty() ? toClass : toPackage + "." + toClass;
        var hasImport = new ArrayList<Path>();
        for (var file : allFiles) {
            if (cacheContainsImport.needs(file, qName)) {
                var found = StringSearch.containsImport(file, toPackage, toClass);
                cacheContainsImport.load(file, qName, found);
            }
            if (cacheContainsImport.get(file, qName)) {
                hasImport.add(file);
            }
        }
        LOG.info(String.format("...%d files import %s.%s", hasImport.size(), toPackage, toClass));

        return hasImport;
    }

    static String className(TreePath t) {
        while (t != null) {
            if (t.getLeaf() instanceof ClassTree) {
                var cls = (ClassTree) t.getLeaf();
                return cls.getSimpleName().toString();
            }
            t = t.getParentPath();
        }
        return "";
    }

    static String memberName(TreePath t) {
        if (t.getLeaf() instanceof ClassTree) {
            return "";
        } else if (t.getLeaf() instanceof MethodTree) {
            var method = (MethodTree) t.getLeaf();
            var name = method.getName().toString();
            if (name.equals("<init>")) {
                return className(t);
            }
            return name;
        } else if (t.getLeaf() instanceof VariableTree) {
            var field = (VariableTree) t.getLeaf();
            var name = field.getName().toString();
            return name;
        }
        return "";
    }

    static String signature(TreePath t) {
        var uri = t.getCompilationUnit().getSourceFile().toUri();
        var packageName = FileStore.packageName(Paths.get(uri));
        var className = className(t);
        var qualifiedName = packageName.isEmpty() ? className : packageName + "." + className;
        if (t.getLeaf() instanceof MethodTree) {
            var memberName = memberName(t);
            var method = (MethodTree) t.getLeaf();
            var params = new StringJoiner(", ");
            for (var param : method.getParameters()) {
                params.add(param.getType().toString());
            }
            return qualifiedName + "#" + memberName + "(" + params + ")";
        } else if (t.getLeaf() instanceof VariableTree) {
            var field = (VariableTree) t.getLeaf();
            return qualifiedName + "#" + field.getName();
        } else {
            return qualifiedName;
        }
    }

    static String asMarkdown(List<? extends DocTree> lines) {
        var join = new StringJoiner("\n");
        for (var l : lines) join.add(l.toString());
        var html = join.toString();
        return TipFormatter.asMarkdown(html);
    }

    static String asMarkdown(DocCommentTree comment) {
        var lines = comment.getFirstSentence();
        return asMarkdown(lines);
    }

    static MarkupContent asMarkupContent(DocCommentTree comment) {
        var markdown = asMarkdown(comment);
        var content = new MarkupContent();
        content.kind = MarkupKind.Markdown;
        content.value = markdown;
        return content;
    }

    private static final Logger LOG = Logger.getLogger("main");
}
