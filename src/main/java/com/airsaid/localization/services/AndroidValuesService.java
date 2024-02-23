/*
 * Copyright 2021 Airsaid. https://github.com/airsaid
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.airsaid.localization.services;

import com.airsaid.localization.translate.lang.Lang;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.xml.XmlDocument;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Operation service for the android value files. eg: strings.xml (or any string
 * resource from values directory).
 *
 * @author airsaid
 */
@Service
public final class AndroidValuesService {

  private static final Logger LOG = Logger.getInstance(AndroidValuesService.class);

  private static final Pattern STRINGS_FILE_NAME_PATTERN = Pattern.compile(".+\\.xml");

  private boolean isSkipNonTranslatable;

  /**
   * Returns the {@link AndroidValuesService} object instance.
   *
   * @return the {@link AndroidValuesService} object instance.
   */
  public static AndroidValuesService getInstance() {
    return ServiceManager.getService(AndroidValuesService.class);
  }

  /**
   * Asynchronous loading the value file as the {@link PsiElement} collection.
   *
   * @param valueFile the value file.
   * @param consumer  load result. called in the event dispatch thread.
   */
  public void loadValuesByAsync(@NotNull PsiFile valueFile, @NotNull Consumer<List<PsiElement>> consumer) {
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      List<PsiElement> values = loadValues(valueFile);
      ApplicationManager.getApplication().invokeLater(() -> consumer.consume(values));
    });
  }

  /**
   * Loading the value file as the {@link PsiElement} collection.
   *
   * @param valueFile the value file.
   * @return {@link PsiElement} collection.
   */
  public List<PsiElement> loadValues(@NotNull PsiFile valueFile) {
    return ApplicationManager.getApplication().runReadAction((Computable<List<PsiElement>>) () -> {
      LOG.info("loadValues valueFile: " + valueFile.getName());
      List<PsiElement> values = parseValuesXml(valueFile);
      LOG.info("loadValues parsed " + values.size() + " items from " + valueFile.getName());
      return values;
    });
  }

  public boolean isSkipNonTranslatable() {
    return isSkipNonTranslatable;
  }

  public void setSkipNonTranslatable(boolean isSkipNonTranslatable) {
    this.isSkipNonTranslatable = isSkipNonTranslatable;
  }

  private List<PsiElement> parseValuesXml(@NotNull PsiFile valueFile) {
    if (!(valueFile instanceof XmlFile))
      return Collections.emptyList();

    final XmlFile xmlFile = (XmlFile) valueFile;
    final XmlDocument document = xmlFile.getDocument();
    if (document == null)
      return Collections.emptyList();

    final XmlTag rootTag = document.getRootTag();
    if (rootTag == null)
      return Collections.emptyList();

    List<PsiElement> values = new ArrayList<>();
    for (PsiElement element : rootTag.getSubTags()) {
      if (!(element instanceof XmlTag))
        continue;
      XmlTag tag = (XmlTag) element;
      if (!isSkipNonTranslatable() || isTranslatable(tag)) {
        values.add(element);
      }
    }
    return values;
  }

  /**
   * Write {@link PsiElement} collection data to the specified file.
   *
   * @param values    specified {@link PsiElement} collection data.
   * @param valueFile specified file.
   */
  public void writeValueFile(@NotNull List<PsiElement> values, @NotNull File valueFile) {
    ApplicationManager.getApplication().invokeLater(() -> ApplicationManager.getApplication().runWriteAction(() -> {
      try (BufferedWriter bw = new BufferedWriter(
          new OutputStreamWriter(new FileOutputStream(valueFile, false), StandardCharsets.UTF_8))) {
        for (PsiElement value : values) {
          bw.write(value.getText() + System.lineSeparator());
        }
      } catch (IOException e) {
        LOG.error("Failed to write to " + valueFile.getPath(), e);
      }
    }));
  }

  /**
   * Verify that the specified file is a string resource file in the values
   * directory.
   *
   * @param file the verify file.
   * @return true: the file is a string resource file in the values directory.
   */
  public boolean isValueFile(@Nullable PsiFile file) {
    if (file == null || !(file.getParent() instanceof PsiDirectory))
      return false;

    String parentName = file.getParent().getName();
    if (!parentName.startsWith("values"))
      return false;

    String fileName = file.getName();
    return STRINGS_FILE_NAME_PATTERN.matcher(fileName).matches();
  }

  /**
   * Get the value file of the specified language in the specified project
   * resource directory.
   *
   * @param project     current project.
   * @param resourceDir specified resource directory.
   * @param lang        specified language.
   * @param fileName    the name of value file.
   * @return null if not exist, otherwise return the value file.
   */
  @Nullable
  public PsiFile getValuePsiFile(@NotNull Project project, @NotNull VirtualFile resourceDir, @NotNull Lang lang,
      @NotNull String fileName) {
    return ApplicationManager.getApplication().runReadAction((Computable<PsiFile>) () -> {
      VirtualFile virtualFile = LocalFileSystem.getInstance()
          .findFileByIoFile(getValueFile(resourceDir, lang, fileName));
      if (virtualFile == null)
        return null;
      return PsiManager.getInstance(project).findFile(virtualFile);
    });
  }

  /**
   * Get the value file in the {@code values} directory of the specified language
   * in the resource directory.
   *
   * @param resourceDir specified resource directory.
   * @param lang        specified language.
   * @param fileName    the name of value file.
   * @return the value file.
   */
  @NotNull
  public File getValueFile(@NotNull VirtualFile resourceDir, @NotNull Lang lang, @NotNull String fileName) {
    return new File(resourceDir.getPath(), getValuesDirectoryName(lang) + File.separator + fileName);
  }

  private String getValuesDirectoryName(@NotNull Lang lang) {
    String[] parts = lang.getCode().split("-");
    if (parts.length > 1) {
      return "values-".concat(parts[0] + "-" + "r" + parts[1].toUpperCase());
    } else {
      return "values-".concat(lang.getCode());
    }
  }

  /**
   * Returns whether the specified xml tag (string entry) needs to be translated.
   *
   * @param xmlTag the specified xml tag of string entry.
   * @return true: need translation. false: no translation is needed.
   */
  public boolean isTranslatable(@NotNull XmlTag xmlTag) {
    return !"false".equals(xmlTag.getAttributeValue("translatable", "true"));
  }
}
