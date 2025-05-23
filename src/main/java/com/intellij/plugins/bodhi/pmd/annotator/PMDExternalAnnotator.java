package com.intellij.plugins.bodhi.pmd.annotator;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.intellij.plugins.bodhi.pmd.ConfigOption;
import com.intellij.plugins.bodhi.pmd.PMDProjectComponent;
import com.intellij.plugins.bodhi.pmd.core.PMDResultCollector;
import com.intellij.psi.PsiFile;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.LanguageVersion;
import net.sourceforge.pmd.lang.document.FileId;
import net.sourceforge.pmd.lang.document.TextFile;
import net.sourceforge.pmd.lang.document.TextFileContent;
import net.sourceforge.pmd.reporting.RuleViolation;
import net.sourceforge.pmd.util.AssertionUtil;
import net.sourceforge.pmd.util.StringUtil;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Display PMD violations in the editor and in the problem view
 */
public class PMDExternalAnnotator extends ExternalAnnotator<FileInfo, PMDAnnotations> {
    private static final Log log = LogFactory.getLog(PMDExternalAnnotator.class);

    @Override
    public FileInfo collectInformation(@NotNull PsiFile file, @NotNull Editor editor, boolean hasErrors) {
        PMDProjectComponent projectComponent = file.getProject().getService(PMDProjectComponent.class);

        LanguageVersion version;
        String fileExtension = file.getName().substring(file.getName().lastIndexOf('.') + 1);
        if ("kt".equalsIgnoreCase(fileExtension) || "kts".equalsIgnoreCase(fileExtension)) {
            // Kotlin file
            String languageVersion = projectComponent.getOptionToValue().get(ConfigOption.TARGET_KOTLIN_VERSION);
            version = LanguageRegistry.PMD.getLanguageVersionById("kotlin", languageVersion);
        } else {
            // Java file (default)
            String languageVersion = projectComponent.getOptionToValue().get(ConfigOption.TARGET_JDK);
            version = LanguageRegistry.PMD.getLanguageVersionById("java", languageVersion);
        }

        return new FileInfo(file, editor.getDocument(), version);
    }

    @Override
    public @Nullable PMDAnnotations doAnnotate(FileInfo info) {
        PMDProjectComponent projectComponent = info.getProject().getService(PMDProjectComponent.class);

        Set<String> inEditorAnnotationActiveRuleSets = projectComponent.getInEditorAnnotationRuleSets().stream()
                .filter(ruleSetPath -> isRuleSetForGivenFile(info, ruleSetPath))
                .collect(Collectors.toSet());

        if (inEditorAnnotationActiveRuleSets.isEmpty()) {
            return null;
        }

        PMDResultCollector collector = new PMDResultCollector();
        PMDAnnotationRenderer renderer = new PMDAnnotationRenderer();
        for (String ruleSetPath : inEditorAnnotationActiveRuleSets) {
            if (isRuleSetForGivenFile(info, ruleSetPath)) {
                collector.runPMDAndGetResults(List.of(), List.of(asTextFile(info)), ruleSetPath, projectComponent, renderer);
            }
        }

        return renderer.getResult(info.getDocument());
    }

    private static boolean isRuleSetForGivenFile(FileInfo info, String ruleSetPath) {
        // This is a very basic check to see if RuleSet applies to the file:
        // it assumes the language id (e.g. "java" or "kotlin") is exclusively part of rule set path
        // (e.g. /category/java/bestpractices.xml or /home/user/jpinpoint-java-rules.xml).
        // This can fail if for (unexpected?) paths like: /home/user/kotlin/jpinpoint-java-rules.xml
        return ruleSetPath.contains(info.getLanguageVersion().getLanguage().getId());
    }

    @Override
    public void apply(@NotNull PsiFile file, PMDAnnotations annotationResult, @NotNull AnnotationHolder holder) {
        if (annotationResult == null) {
            return;
        }

        Document document = annotationResult.document();
        for (RuleViolation violation : annotationResult.report().getViolations()) {
            int startLineOffset = document.getLineStartOffset(violation.getBeginLine()-1);
            int endOffset = violation.getEndLine() - violation.getBeginLine() > 5 // Only mark first line for long violations
                    ? document.getLineEndOffset(violation.getBeginLine()-1)
                    : document.getLineStartOffset(violation.getEndLine()-1) + violation.getEndColumn();

            try {
                var textRange = TextRange.create(startLineOffset + violation.getBeginColumn() - 1, endOffset);
                holder.newAnnotation(getSeverity(violation), "PMD: " + violation.getDescription())
                        .tooltip("PMD: " + violation.getRule().getName() +
                                "<p>" + violation.getDescription() +
                                "</p><p>" + violation.getRule().getDescription() + "</p>")
                        .range(textRange)
                        .needsUpdateOnTyping(true)
                        .withFix(new SupressIntentionAction(violation))
                        .create();
            } catch(IllegalArgumentException e) {
                // Catching "Invalid range specified" from TextRange.create thrown when file has been updated while analyzing
                log.warn("Error while annotating file with PMD warnings: " + e.getMessage());
            }
        }
    }

    private TextFile asTextFile(FileInfo info) {
        return new StringTextFile(
                info.getDocument().getText(),
                FileId.UNKNOWN,
                info.getLanguageVersion()
        );
    }

    private static HighlightSeverity getSeverity(RuleViolation violation) {
        return switch (violation.getRule().getPriority()) {
            case HIGH -> HighlightSeverity.ERROR;
            case MEDIUM_HIGH, MEDIUM -> HighlightSeverity.WARNING;
            case MEDIUM_LOW -> HighlightSeverity.WEAK_WARNING;
            case LOW -> HighlightSeverity.INFORMATION;
        };
    }

    // Copied from PMD's StringTextFile since it was not public
    private static class StringTextFile implements TextFile {
        private final TextFileContent content;
        private final FileId fileId;
        private final LanguageVersion languageVersion;

        StringTextFile(CharSequence source, FileId fileId, LanguageVersion languageVersion) {
            AssertionUtil.requireParamNotNull("source text", source);
            AssertionUtil.requireParamNotNull("file name", fileId);
            AssertionUtil.requireParamNotNull("language version", languageVersion);

            this.languageVersion = languageVersion;
            this.content = TextFileContent.fromCharSeq(source);
            this.fileId = fileId;
        }

        @Override
        public @NonNull LanguageVersion getLanguageVersion() {
            return languageVersion;
        }

        @Override
        public FileId getFileId() {
            return fileId;
        }

        @Override
        public TextFileContent readContents() {
            return content;
        }

        @Override
        public void close() {
            // nothing to do
        }

        @Override
        public String toString() {
            return "ReadOnlyString[" + StringUtil.elide(content.getNormalizedText().toString(), 40, "...") + "]";
        }
    }
}
