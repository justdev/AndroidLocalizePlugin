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

package com.airsaid.localization.translate.impl.openai;

import com.airsaid.localization.config.SettingsState;
import com.airsaid.localization.translate.AbstractTranslator;
import com.airsaid.localization.translate.lang.Lang;
import com.airsaid.localization.translate.lang.Languages;
import com.airsaid.localization.translate.util.GsonUtil;
import com.google.auto.service.AutoService;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.io.RequestBuilder;
import icons.PluginIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.List;

@AutoService(AbstractTranslator.class)
public class ChatGPTTranslator extends AbstractTranslator {

    private static final Logger LOG = Logger.getInstance(ChatGPTTranslator.class);
    private static final String API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String KEY = "ChatGPT";

    @Override
    public @NotNull String getKey() {
        return KEY;
    }

    @Override
    public @NotNull String getName() {
        return "OpenAI ChatGPT";
    }

    @Override
    public @Nullable Icon getIcon() {
        return PluginIcons.OPENAI_ICON;
    }

    @Override
    public boolean isNeedAppId() {
        return false;
    }

    @Override
    public boolean isNeedAppKey() {
        return true;
    }

    @Override
    public @NotNull List<Lang> getSupportedLanguages() {
        return Languages.getLanguages();
    }

    @Override
    public String getAppKeyDisplay() {
        return "API Key";
    }

    @Override
    public @NotNull String getRequestUrl(@NotNull Lang fromLang, @NotNull Lang toLang, @NotNull String text) {
        return API_URL; // Ensure this is the correct API endpoint
    }

    @Override
    @NotNull
    public String getRequestBody(@NotNull Lang fromLang, @NotNull Lang toLang, @NotNull String text) {
        String lang = toLang.getEnglishName();
        String escapedText = text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");

        String roleSystem = String.format(
                "Translate the user provided text into high quality, well written %s. Apply these 4 translation rules; 1.Keep the exact original formatting and style, 2.Keep translations concise and just repeat the original text for unchanged translations (e.g. 'OK'), 3.Audience: native %s speakers, 4.Text can be used in Android app UI (limited space, concise translations!).",
                lang, lang);

        ChatGPTMessage role = new ChatGPTMessage("system", roleSystem);
        ChatGPTMessage msg = new ChatGPTMessage("user", String.format("Text to translate: %s", escapedText));

        String chatGptModel = SettingsState.getInstance().getChatGPTModel();
        OpenAIRequest body = new OpenAIRequest(chatGptModel, List.of(role, msg));

        return GsonUtil.getInstance().getGson().toJson(body);
    }

    @Override
    public void configureRequestBuilder(@NotNull RequestBuilder requestBuilder) {
        requestBuilder.tuner(connection -> {
            connection.setRequestProperty("Authorization", "Bearer " + getAppKey());
            connection.setRequestProperty("Content-Type", "application/json");
        });
    }

    @Override
    public @NotNull String parsingResult(@NotNull Lang fromLang, @NotNull Lang toLang, @NotNull String text,
            @NotNull String resultText) {
        // Safely parse the JSON response
        try {
            OpenAIResponse response = GsonUtil.getInstance().getGson().fromJson(resultText, OpenAIResponse.class);
            if (response == null || response.getTranslation() == null) {
                LOG.error("No translation found in response: " + resultText);
                return text; // Fallback to original text
            }
            return response.getTranslation();
        } catch (Exception e) {
            LOG.error("Error parsing translation result: " + e.getMessage(), e);
            return text; // Fallback to original text in case of parsing errors
        }
    }

}
