package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.util.StringJoiner;
import java.util.concurrent.ExecutionException;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.junit.Ignore;
import org.junit.Test;

public class SymbolUnderCursorTest {

    @Test
    public void classDeclaration() {
        assertThat(
                symbolAt("/org/javacs/example/SymbolUnderCursor.java", 3, 22),
                containsString("org.javacs.example.SymbolUnderCursor"));
    }

    @Test
    public void fieldDeclaration() {
        assertThat(symbolAt("/org/javacs/example/SymbolUnderCursor.java", 4, 22), containsString("field"));
    }

    @Test
    public void methodDeclaration() {
        assertThat(
                symbolAt("/org/javacs/example/SymbolUnderCursor.java", 6, 22),
                containsString("method(String methodParameter)"));
    }

    @Test
    public void methodParameterDeclaration() {
        assertThat(symbolAt("/org/javacs/example/SymbolUnderCursor.java", 6, 36), containsString("methodParameter"));
    }

    @Test
    public void localVariableDeclaration() {
        assertThat(symbolAt("/org/javacs/example/SymbolUnderCursor.java", 7, 22), containsString("localVariable"));
    }

    @Test
    public void constructorParameterDeclaration() {
        assertThat(
                symbolAt("/org/javacs/example/SymbolUnderCursor.java", 17, 46), containsString("constructorParameter"));
    }

    @Test
    public void classIdentifier() {
        assertThat(
                symbolAt("/org/javacs/example/SymbolUnderCursor.java", 12, 23),
                containsString("org.javacs.example.SymbolUnderCursor"));
    }

    @Test
    public void fieldIdentifier() {
        assertThat(symbolAt("/org/javacs/example/SymbolUnderCursor.java", 9, 27), containsString("field"));
    }

    @Test
    public void methodIdentifier() {
        assertThat(
                symbolAt("/org/javacs/example/SymbolUnderCursor.java", 12, 12),
                containsString("method(String methodParameter)"));
    }

    @Test
    public void methodSelect() {
        assertThat(
                symbolAt("/org/javacs/example/SymbolUnderCursor.java", 13, 17),
                containsString("method(String methodParameter)"));
    }

    @Ignore // tree.sym is null
    @Test
    public void methodReference() {
        assertThat(symbolAt("/org/javacs/example/SymbolUnderCursor.java", 14, 65), containsString("method"));
    }

    @Test
    public void annotationUse() {
        var found = symbolAt("/org/javacs/example/SymbolUnderCursor.java", 21, 8);
        assertThat(found, containsString("@interface Override"));
        assertThat(found, not(containsString("extends none")));
    }

    @Test
    public void methodParameterReference() {
        assertThat(symbolAt("/org/javacs/example/SymbolUnderCursor.java", 10, 32), containsString("methodParameter"));
    }

    @Test
    public void localVariableReference() {
        assertThat(symbolAt("/org/javacs/example/SymbolUnderCursor.java", 10, 16), containsString("localVariable"));
    }

    // Re-using the language server makes these tests go a lot faster, but it will potentially produce surprising output
    // if things go wrong
    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    private String symbolAt(String file, int line, int character) {
        var pos =
                new TextDocumentPositionParams(
                        new TextDocumentIdentifier(FindResource.uri(file).toString()),
                        new Position(line - 1, character - 1));
        var result = new StringJoiner("\n");
        try {
            server.getTextDocumentService()
                    .hover(pos)
                    .get()
                    .getContents()
                    .getLeft()
                    .forEach(hover -> result.add(hover.isLeft() ? hover.getLeft() : hover.getRight().getValue()));
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
        return result.toString();
    }
}
