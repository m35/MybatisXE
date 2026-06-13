package com.baomidou.plugin.idea.mybatisx.system.contributor;

import com.baomidou.plugin.idea.mybatisx.dom.model.Mapper;
import com.baomidou.plugin.idea.mybatisx.inspection.MapperMethodInspection;
import com.baomidou.plugin.idea.mybatisx.smartjpa.component.mapping.CommentAnnotationMappingResolver;
import com.baomidou.plugin.idea.mybatisx.smartjpa.ui.SmartJpaCompletionProvider;
import com.baomidou.plugin.idea.mybatisx.util.MapperUtils;
import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.highlighter.HighlighterIterator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.util.Key;
import com.intellij.psi.CustomHighlighterTokenType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiInvalidElementAccessException;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReferenceList;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * mapper的jpa方法提示
 */
public class MapperMethodCompletionContributor extends CompletionContributor {

    /**
     * The constant FOUND.
     */
    public static final Key<Boolean> FOUND = Key.create("mapper.found");

    /**
     * The constant MAPPER.
     */
    public static final Key<PsiClass> MAPPER = Key.create("mapper.mapper");
    private static final Logger logger = LoggerFactory.getLogger(MapperMethodCompletionContributor.class);

    private static boolean inCommentOrLiteral(CompletionParameters parameters) {
        HighlighterIterator iterator = ((EditorEx) parameters.getEditor()).getHighlighter().createIterator(parameters.getOffset());
        if (iterator.atEnd()) {
            return false;
        }

        IElementType elementType = iterator.getTokenType();
        if (elementType == CustomHighlighterTokenType.WHITESPACE) {
            iterator.retreat();
            elementType = iterator.getTokenType();
        }
        return elementType == CustomHighlighterTokenType.LINE_COMMENT ||
            elementType == CustomHighlighterTokenType.MULTI_LINE_COMMENT ||
            elementType == CustomHighlighterTokenType.STRING ||
            elementType == CustomHighlighterTokenType.SINGLE_QUOTED_STRING;
    }

    /**
     * 填充变量
     * <p>
     * 这一层做验证
     *
     * @param parameters
     * @param result
     */
    @Override
    public void fillCompletionVariants(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
        super.fillCompletionVariants(parameters, result);
        Editor editor = parameters.getEditor();
        Boolean found = editor.getUserData(FOUND);
        if (found != null && !found) {
            return;
        }
        PsiClass mapperClass = editor.getUserData(MAPPER);
        if (mapperClass == null) {
            PsiClass foundMapperClass = PsiTreeUtil.getParentOfType(parameters.getOriginalPosition(), PsiClass.class);
            Optional<PsiClass> mapperClassOptional = getIfIsMapper(foundMapperClass);
            if (mapperClassOptional.isPresent()) {
                mapperClass = mapperClassOptional.get();
                editor.putUserData(MAPPER, mapperClass);
                editor.putUserData(FOUND, true);
            } else {
                editor.putUserData(FOUND, false);
                return;
            }
        }
        if (!checkPosition(parameters)) {
            logger.info("JPA code completion is not working (incorrect location).");
            return;
        }

        logger.info("MapperMethodCompletionContributor.fillCompletionVariants start");

        try {
            SmartJpaCompletionProvider smartJpaCompletionProvider = new SmartJpaCompletionProvider();
            smartJpaCompletionProvider.addCompletion(parameters, result, mapperClass);
        } catch (ProcessCanceledException e) {
            // IntelliJ has explicit instructions for this exception:
            // "ProcessCanceledException and its inheritors must not be caught,
            // swallowed, logged, or handled in any way. Instead, it must be rethrown
            // so that the IntelliJ Platform infrastructure can handle it correctly."
            logger.info("Unknown cancellation reason", e); // Logging anyway
            throw e;
        } catch (PsiInvalidElementAccessException e) {
            logger.info("Unable to access the node.", e);
        } catch (Throwable e) {
            logger.error("Automatic anomaly notification", e);
        }

        logger.info("MapperMethodCompletionContributor.fillCompletionVariants end");

    }

    /**
     * Find mapper class optional.
     *
     * @param mapperClass the mapperClass
     * @return the optional
     */
    @NotNull
    protected Optional<PsiClass> getIfIsMapper(PsiClass mapperClass) {
        if (mapperClass == null) {
            return Optional.empty();
        }
        Optional<Mapper> firstMapper = MapperUtils.findFirstMapper(mapperClass.getProject(), mapperClass);
        if (firstMapper.isPresent()) {
            return Optional.of(mapperClass);
        }
        logger.info("The current class is not a mapper interface; no suggestion will be shown. Class: " + mapperClass.getQualifiedName());
        Optional<PsiClass> psiMapper = getMapperIfHasAnnotation(mapperClass);
        if (psiMapper.isPresent()) {
            return psiMapper;
        }
        return getMapperIfExtendsFromMybatisPlus(mapperClass);


    }

    @NotNull
    private Optional<PsiClass> getMapperIfHasAnnotation(PsiClass mapperClass) {
        // 支持 mapper 接口上面写 @Entity 注解
        PsiDocComment docComment = mapperClass.getDocComment();
        if (docComment != null && docComment.findTagByName(CommentAnnotationMappingResolver.TABLE_ENTITY) != null) {
            return Optional.of(mapperClass);
        }
        return Optional.empty();
    }

    @NotNull
    private Optional<PsiClass> getMapperIfExtendsFromMybatisPlus(PsiClass mapperClass) {
        final PsiReferenceList extendsList = mapperClass.getExtendsList();
        if (extendsList != null) {
            final PsiJavaCodeReferenceElement[] referenceElements = extendsList.getReferenceElements();
            for (PsiJavaCodeReferenceElement referenceElement : referenceElements) {
                final String qualifiedName = referenceElement.getQualifiedName();
                if (MapperMethodInspection.MYBATIS_PLUS_BASE_MAPPER_NAMES.contains(qualifiedName)) {
                    return Optional.of(mapperClass);
                }
            }
        }
        return Optional.empty();
    }


    private boolean checkPosition(CompletionParameters parameters) {
        if (parameters.getCompletionType() != CompletionType.BASIC) {
            logger.info("The type is not BASIC.");
            return false;
        }

        // 验证当前类必须是接口
        PsiElement originalPosition = parameters.getOriginalPosition();

        PsiMethod currentMethod = PsiTreeUtil.getParentOfType(originalPosition, PsiMethod.class);
        if (currentMethod != null) {
            logger.info("Currently located inside the method body; no suggestions are displayed.");
            return false;
        }

        PsiClass mapperClass = PsiTreeUtil.getParentOfType(originalPosition, PsiClass.class);
        if (mapperClass == null || !mapperClass.isInterface()) {
            logger.info("The current class is not an interface; no suggestion is displayed.");
            return false;
        }
        if (inCommentOrLiteral(parameters)) {
            logger.info("No prompts in comment ranges");
            return false;
        }
        return true;
    }

}
