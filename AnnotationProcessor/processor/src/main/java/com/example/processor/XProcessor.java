package com.example.processor;

import com.example.annotation.Activity;
import com.example.annotation.Fragment;
import com.example.annotation.Param;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

/**
 * Created by Wai Yan on 1/5/19.
 */

@SupportedAnnotationTypes({
        "com.example.annotation.Activity",
        "com.example.annotation.Fragment",
        "com.example.annotation.Param",
})
public class XProcessor extends AbstractProcessor {

    private static final String PACKAGE_NAME = "wyphyoe.annotationintro";

    private static final String CLASS_NAME_INTENT_FACTORY = "IntentFactory";
    private static final String CLASS_NAME_INSTANCE_FACTORY = "InstanceFactory";

    private static final ClassName intentClass = ClassName.get("android.content", "Intent");
    private static final ClassName contextClass = ClassName.get("android.content", "Context");
    private static final ClassName bundleClass = ClassName.get("android.os", "Bundle");

    private static final String METHOD_PREFIX_NEW_INTENT = "newIntentFor";
    private static final String METHOD_PREFIX_NEW_INSTANCE = "newInstanceOf";

    private static final String PARAM_NAME_CONTEXT = "context";
    private static final String CLASS_SUFFIX = ".class";

    private static final String BUNDLE_PUT_METHOD_PREFIX = "args.put";

    private static final String PARCELABLE_STATEMENT = "args.putParcelable";
    private static final String SERIALIZABLE_STATEMENT = "args.putSerializable";
    private static final String SIMPLE_NAME_BINDER = "IBinder";

    private final List<MethodSpec> newIntentMethodSpecs = new ArrayList<>();
    private final List<MethodSpec> newInstanceMethodSpecs = new ArrayList<>();
    private static final List<String> availableClasses =
            Arrays.asList("string", "binder", "bundle", "size", "sizeF");

    private boolean HALT = false;
    private int round = -1;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> set, RoundEnvironment roundEnvironment) {

        round++;

        if (round == 0) {
            EnvironmentUtil.init(processingEnv);
        }

        if (!processAnnotations(roundEnvironment)) {
            return HALT;
        }

        if (roundEnvironment.processingOver()) {
            try {
                createIntentFactory();
                createInstanceFactory();
                HALT = true;
            } catch (IOException ex) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, ex.toString());
            }
        }

        return HALT;
    }

    private boolean processAnnotations(RoundEnvironment roundEnv) {
        return processActivities(roundEnv) && processFragments(roundEnv);
    }

    private boolean processActivities(RoundEnvironment roundEnv) {

        final Set<? extends Element> elements =
                roundEnv.getElementsAnnotatedWith(Activity.class);

        if (Utils.isNullOrEmpty(elements)) {
            return true;
        }

        for (Element element : elements) {
            if (element.getKind() != ElementKind.CLASS) {
                EnvironmentUtil.logError("Activity can only be used for classes!");
                return false;
            }

            if (!generateNewIntentMethod((TypeElement) element)) {
                return false;
            }
        }

        return true;
    }

    private boolean processFragments(RoundEnvironment roundEnv) {

        final Set<? extends Element> elements =
                roundEnv.getElementsAnnotatedWith(Fragment.class);

        if (Utils.isNullOrEmpty(elements)) {
            return true;
        }

        for (Element element : elements) {
            if (element.getKind() != ElementKind.CLASS) {
                EnvironmentUtil.logError("Fragment can only be used for classes!");
                return false;
            }

            if (!generateNewInstanceMethod((TypeElement) element)) {
                return false;
            }
        }

        return true;
    }

    private boolean generateNewIntentMethod(TypeElement element) {

        final MethodSpec.Builder navigationMethodSpecBuilder = MethodSpec
                .methodBuilder(METHOD_PREFIX_NEW_INTENT + element.getSimpleName())
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(intentClass)
                .addParameter(contextClass, PARAM_NAME_CONTEXT);

        final List<KeyElementPair> pairs = findParamFields(element);
        if (!Utils.isNullOrEmpty(pairs)) {
            navigationMethodSpecBuilder.addStatement("final $T intent = new $T($L, $L)",
                    intentClass,
                    intentClass,
                    PARAM_NAME_CONTEXT,
                    element.getSimpleName() + CLASS_SUFFIX);
            for (KeyElementPair pair : pairs) {
                navigationMethodSpecBuilder.addParameter(ClassName.get(pair.element.asType()),
                        pair.element.getSimpleName().toString());
                navigationMethodSpecBuilder.addStatement("intent.putExtra($S, $L)",
                        pair.key,
                        pair.element);
            }
            navigationMethodSpecBuilder.addStatement("return intent");
        } else {
            navigationMethodSpecBuilder.addStatement("return new $T($L, $L)",
                    intentClass,
                    PARAM_NAME_CONTEXT,
                    element.getQualifiedName() + CLASS_SUFFIX);
        }

        newIntentMethodSpecs.add(navigationMethodSpecBuilder.build());
        return true;
    }

    private boolean generateNewInstanceMethod(TypeElement element) {

        final MethodSpec.Builder instanceMethodSpecBuilder = MethodSpec
                .methodBuilder(METHOD_PREFIX_NEW_INSTANCE + element.getSimpleName())
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ClassName.get(element));

        final TypeName returnType = ClassName.get(element);
        final List<KeyElementPair> pairs = findParamFields(element);
        if (!Utils.isNullOrEmpty(pairs)) {
            instanceMethodSpecBuilder.addStatement("final $T args = new $T()",
                    bundleClass,
                    bundleClass);
            for (KeyElementPair pair : pairs) {
                final String statementSuffix = "($S, $L)";
                final TypeName typeName = ClassName.get(pair.element.asType());
                instanceMethodSpecBuilder.addParameter(typeName,
                        pair.element.getSimpleName().toString());
                instanceMethodSpecBuilder.addStatement(
                        generateArgsStatement((VariableElement) pair.element) + statementSuffix,
                        pair.key,
                        pair.element);
            }
            instanceMethodSpecBuilder.addStatement("final $T instance = new $T()",
                    returnType,
                    returnType);
            instanceMethodSpecBuilder.addStatement("instance.setArguments(args)");
            instanceMethodSpecBuilder.addStatement("return instance");
        } else {
            instanceMethodSpecBuilder.addStatement("return new $T()", returnType);
        }

        newInstanceMethodSpecs.add(instanceMethodSpecBuilder.build());
        return true;
    }

    private List<KeyElementPair> findParamFields(Element parent) {

        final List<? extends Element> citizens = parent.getEnclosedElements();
        if (Utils.isNullOrEmpty(citizens)) return null;

        final List<KeyElementPair> pairs = new ArrayList<>();
        for (Element citizen : citizens) {
            final Param annotation = citizen.getAnnotation(Param.class);
            if (annotation != null) {
                if (Utils.isNullOrEmpty(annotation.key())) {
                    EnvironmentUtil.logWarning(
                            "Using Param Annotation without a Key! Field'll be ignored! " +
                                    citizen.getSimpleName() + " in " + parent.getSimpleName());
                    continue;
                }
                pairs.add(new KeyElementPair(annotation.key(), citizen));
            }
        }

        return pairs;
    }

    private static String generateArgsStatement(VariableElement element) {

        final TypeMirror typeMirror = element.asType();
        final TypeName typeName = ClassName.get(typeMirror);
        final String simpleName = element.getSimpleName().toString();

        if (typeName.isPrimitive()) {
            return generatePutStatementForPrimitives(typeName);
        }

        if (typeName.isBoxedPrimitive()) {
            return generatePutStatementForPrimitives(typeName.unbox());
        }

        if (availableClasses.contains(simpleName)) {
            if (SIMPLE_NAME_BINDER.equals(simpleName)) {
                return BUNDLE_PUT_METHOD_PREFIX + SIMPLE_NAME_BINDER.substring(1);
            }

            return generatePutStatement(simpleName);
        }

        if (EnvironmentUtil.isParcelable(typeMirror)) {
            return PARCELABLE_STATEMENT;
        }

        if (EnvironmentUtil.isSerializable(typeMirror)) {
            return SERIALIZABLE_STATEMENT;
        }

        EnvironmentUtil.logError(simpleName);
        throw new IllegalArgumentException("Unsupported type!");
    }

    private static String generatePutStatementForPrimitives(TypeName typeName) {

        if (!typeName.isPrimitive()) {
            throw new IllegalArgumentException("Type must be primitive!");
        }

        return generatePutStatement(typeName.toString());
    }

    private static String generatePutStatement(String simpleName) {

        final StringBuilder statementBuilder = new StringBuilder();
        statementBuilder.append(BUNDLE_PUT_METHOD_PREFIX)
                .append(Character.toUpperCase(simpleName.charAt(0)))
                .append(simpleName.substring(1));

        return statementBuilder.toString();
    }

    private void createIntentFactory() throws IOException {

        final TypeSpec.Builder builder = TypeSpec.classBuilder(CLASS_NAME_INTENT_FACTORY);
        builder.addModifiers(Modifier.PUBLIC, Modifier.FINAL);

        for (MethodSpec methodSpec : newIntentMethodSpecs) {
            builder.addMethod(methodSpec);
        }

        EnvironmentUtil.generateFile(builder.build(), PACKAGE_NAME);
    }

    private void createInstanceFactory() throws IOException {

        final TypeSpec.Builder builder = TypeSpec.classBuilder(CLASS_NAME_INSTANCE_FACTORY);
        builder.addModifiers(Modifier.PUBLIC, Modifier.FINAL);

        for (MethodSpec methodSpec : newInstanceMethodSpecs) {
            builder.addMethod(methodSpec);
        }

        EnvironmentUtil.generateFile(builder.build(), PACKAGE_NAME);
    }
}
