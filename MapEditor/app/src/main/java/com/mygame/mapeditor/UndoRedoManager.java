package com.mygame.mapeditor;

import android.content.Context;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.Deque;

public class UndoRedoManager {

    private final Context context;
    private final Runnable invalidator;

    private final Deque<EditorCommand> undoStack = new ArrayDeque<>();
    private final Deque<EditorCommand> redoStack = new ArrayDeque<>();
    private static final int MAX_UNDO_LIMIT = 25;
    public boolean isDirty = false;

    public UndoRedoManager(Context context, Runnable invalidator) {
        this.context = context;
        this.invalidator = invalidator;
    }

    public void executeCommand(EditorCommand cmd) {
        cmd.execute();
        undoStack.push(cmd);
        if (undoStack.size() > MAX_UNDO_LIMIT) {
            undoStack.removeLast();
        }
        redoStack.clear();
        isDirty = true;
        invalidator.run();
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public void undo() {
        if (!undoStack.isEmpty()) {
            EditorCommand cmd = undoStack.pop();
            cmd.undo();
            redoStack.push(cmd);
            invalidator.run();
        } else {
            Toast.makeText(context, context.getString(R.string.toast_no_undo), Toast.LENGTH_SHORT).show();
        }
    }

    public void redo() {
        if (!redoStack.isEmpty()) {
            EditorCommand cmd = redoStack.pop();
            cmd.execute();
            undoStack.push(cmd);
            invalidator.run();
        } else {
            Toast.makeText(context, context.getString(R.string.toast_no_redo), Toast.LENGTH_SHORT).show();
        }
    }
}
