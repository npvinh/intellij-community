// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.fileEditor.impl.text;

import com.intellij.codeHighlighting.BackgroundEditorHighlighter;
import com.intellij.ide.structureView.StructureViewBuilder;
import com.intellij.lang.Language;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import javax.swing.*;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author Vladimir Kondratyev
 */
public class TextEditorImpl extends UserDataHolderBase implements TextEditor {
  private static final Logger LOG = Logger.getInstance(TextEditorImpl.class);

  private static final Key<TransientEditorState> TRANSIENT_EDITOR_STATE_KEY = Key.create("transientState");

  protected final Project myProject;
  private final PropertyChangeSupport myChangeSupport;
  @NotNull private final TextEditorComponent myComponent;
  @NotNull protected final VirtualFile myFile;
  private final AsyncEditorLoader myAsyncLoader;
  private final Future<?> myLoadingFinished;

  TextEditorImpl(@NotNull final Project project, @NotNull final VirtualFile file, final TextEditorProvider provider) {
    myProject = project;
    myFile = file;
    myChangeSupport = new PropertyChangeSupport(this);
    myComponent = createEditorComponent(project, file);

    TransientEditorState state = myFile.getUserData(TRANSIENT_EDITOR_STATE_KEY);
    if (state != null) {
      state.applyTo(getEditor());
      myFile.putUserData(TRANSIENT_EDITOR_STATE_KEY, null);
    }

    Disposer.register(this, myComponent);
    myAsyncLoader = new AsyncEditorLoader(this, myComponent, provider);
    myLoadingFinished = myAsyncLoader.start();
  }

  @NotNull
  protected Runnable loadEditorInBackground() {
    EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
    EditorHighlighter highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(myFile, scheme, myProject);
    EditorEx editor = (EditorEx)getEditor();
    highlighter.setText(editor.getDocument().getImmutableCharSequence());
    Language language = getDocumentLanguage(editor);
    return () -> {
      editor.getSettings().setLanguage(language);
      editor.setHighlighter(highlighter);
    };
  }

  @Nullable
  public static Language getDocumentLanguage(@NotNull Editor editor) {
    Project project = editor.getProject();
    LOG.assertTrue(project != null);
    if (!project.isDisposed()) {
      PsiDocumentManager documentManager = PsiDocumentManager.getInstance(project);
      PsiFile file = documentManager.getPsiFile(editor.getDocument());
      if (file != null) return file.getLanguage();
    }
    else {
      LOG.warn("Attempting to get a language for document on a disposed project: " + project.getName());
    }
    return null;
  }

  @NotNull
  protected TextEditorComponent createEditorComponent(final Project project, final VirtualFile file) {
    return new TextEditorComponent(project, file, this);
  }

  @Override
  public void dispose(){
    if (Boolean.TRUE.equals(myFile.getUserData(FileEditorManagerImpl.CLOSING_TO_REOPEN))) {
      myFile.putUserData(TRANSIENT_EDITOR_STATE_KEY, TransientEditorState.forEditor(getEditor()));
    }
  }

  @NotNull
  @Override
  public VirtualFile getFile() {
    return myFile;
  }

  @Override
  @NotNull
  public TextEditorComponent getComponent() {
    return myComponent;
  }

  @Override
  @NotNull
  public JComponent getPreferredFocusedComponent(){
    return getActiveEditor().getContentComponent();
  }

  @Override
  @NotNull
  public Editor getEditor(){
    return getActiveEditor();
  }

  /**
   * @see TextEditorComponent#getEditor()
   */
  @NotNull
  private Editor getActiveEditor() {
    return myComponent.getEditor();
  }

  @Override
  @NotNull
  public String getName() {
    return "Text";
  }

  @Override
  @NotNull
  public FileEditorState getState(@NotNull FileEditorStateLevel level) {
    return myAsyncLoader.getEditorState(level);
  }

  @Override
  public void setState(@NotNull final FileEditorState state) {
    setState(state, false);
  }

  @Override
  public void setState(@NotNull final FileEditorState state, boolean exactState) {
    myAsyncLoader.setEditorState((TextEditorState)state, exactState);
  }

  @Override
  public boolean isModified() {
    return myComponent.isModified();
  }

  @Override
  public boolean isValid() {
    return myComponent.isEditorValid();
  }

  @Override
  public void selectNotify() {
    myComponent.selectNotify();
  }

  @Override
  public void deselectNotify() {
  }

  public void updateModifiedProperty() {
    myComponent.updateModifiedProperty();
  }

  void firePropertyChange(final String propertyName, final Object oldValue, final Object newValue) {
    myChangeSupport.firePropertyChange(propertyName, oldValue, newValue);
  }

  @Override
  public void addPropertyChangeListener(@NotNull final PropertyChangeListener listener) {
    myChangeSupport.addPropertyChangeListener(listener);
  }

  @Override
  public void removePropertyChangeListener(@NotNull final PropertyChangeListener listener) {
    myChangeSupport.removePropertyChangeListener(listener);
  }

  @Override
  public BackgroundEditorHighlighter getBackgroundHighlighter() {
    return null;
  }

  @Override
  public FileEditorLocation getCurrentLocation() {
    return new TextEditorLocation(getEditor().getCaretModel().getLogicalPosition(), this);
  }

  @Override
  public StructureViewBuilder getStructureViewBuilder() {
    Document document = myComponent.getEditor().getDocument();
    VirtualFile file = FileDocumentManager.getInstance().getFile(document);
    if (file == null || !file.isValid()) return null;
    return StructureViewBuilder.PROVIDER.getStructureViewBuilder(file.getFileType(), file, myProject);
  }

  @Override
  public boolean canNavigateTo(@NotNull Navigatable navigatable) {
    return navigatable instanceof OpenFileDescriptor &&
           (((OpenFileDescriptor)navigatable).getLine() >= 0 || ((OpenFileDescriptor)navigatable).getOffset() >= 0);
  }

  @Override
  public void navigateTo(@NotNull final Navigatable navigatable) {
    ((OpenFileDescriptor)navigatable).navigateIn(getEditor());
  }

  @Override
  public String toString() {
    return "Editor: "+myComponent.getFile();
  }

  @TestOnly
  public void waitForLoaded(long timeout, @NotNull TimeUnit unit) throws TimeoutException {
    try {
      myLoadingFinished.get(timeout, unit);
    }
    catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  private static class TransientEditorState {
    private boolean softWrapsEnabled;

    private static TransientEditorState forEditor(Editor editor) {
      TransientEditorState state = new TransientEditorState();
      state.softWrapsEnabled = editor.getSettings().isUseSoftWraps();
      return state;
    }

    private void applyTo(Editor editor) {
      editor.getSettings().setUseSoftWraps(softWrapsEnabled);
    }
  }
}
