/*
 * Copyright 2011 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.j2objc.translate;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.devtools.j2objc.ast.Block;
import com.google.devtools.j2objc.ast.ClassInstanceCreation;
import com.google.devtools.j2objc.ast.Expression;
import com.google.devtools.j2objc.ast.MethodDeclaration;
import com.google.devtools.j2objc.ast.MethodInvocation;
import com.google.devtools.j2objc.ast.ReturnStatement;
import com.google.devtools.j2objc.ast.SimpleName;
import com.google.devtools.j2objc.ast.SingleVariableDeclaration;
import com.google.devtools.j2objc.ast.StringLiteral;
import com.google.devtools.j2objc.ast.TreeVisitor;
import com.google.devtools.j2objc.ast.TypeDeclaration;
import com.google.devtools.j2objc.types.GeneratedMethodBinding;
import com.google.devtools.j2objc.types.GeneratedVariableBinding;
import com.google.devtools.j2objc.types.IOSMethod;
import com.google.devtools.j2objc.types.IOSMethodBinding;
import com.google.devtools.j2objc.types.JavaMethod;
import com.google.devtools.j2objc.types.Types;
import com.google.devtools.j2objc.util.BindingUtil;
import com.google.devtools.j2objc.util.ErrorUtil;
import com.google.devtools.j2objc.util.NameTable;
import com.google.j2objc.annotations.ObjectiveCName;

import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Modifier;

import java.util.List;
import java.util.Map;

/**
 * Translates invocations of mapped constructors to method invocation nodes.
 * Adds copyWithZone methods to Cloneable types.
 *
 * @author Tom Ball
 */
public class JavaToIOSMethodTranslator extends TreeVisitor {

  private final ITypeBinding javaLangCloneable;

  private final Map<String, IOSMethod> methodMappings;

  private static final Function<String, IOSMethod> IOS_METHOD_FROM_STRING =
      new Function<String, IOSMethod>() {
    public IOSMethod apply(String value) {
      return IOSMethod.create(value);
    }
  };

  public JavaToIOSMethodTranslator(Map<String, String> methodMappings) {
    this.methodMappings =
        Maps.newHashMap(Maps.transformValues(methodMappings, IOS_METHOD_FROM_STRING));
    javaLangCloneable = Types.resolveJavaType("java.lang.Cloneable");
  }

  @Override
  public boolean visit(MethodDeclaration node) {
    IMethodBinding method = node.getMethodBinding();

    // Check if @ObjectiveCName is used but is mismatched with an overriden method.
    IAnnotationBinding annotation = BindingUtil.getAnnotation(method, ObjectiveCName.class);
    if (annotation != null) {
      String value = (String) BindingUtil.getAnnotationValue(annotation, "value");
      String selector =
          IOSMethod.create(method.getDeclaringClass().getName() + " " + value).getSelector();
      String actualSelector = NameTable.getMethodSelector(method);
      if (!selector.equals(actualSelector)) {
        ErrorUtil.warning("ObjectiveCName(" + selector
            + "): Renamed method overrides a method with a different name.");
      }
    }
    return true;
  }

  @Override
  public boolean visit(ClassInstanceCreation node) {
    // translate any embedded method invocations
    if (node.getExpression() != null) {
      node.getExpression().accept(this);
    }
    for (Expression e : node.getArguments()) {
      e.accept(this);
    }
    if (node.getAnonymousClassDeclaration() != null) {
      node.getAnonymousClassDeclaration().accept(this);
    }

    IMethodBinding binding = node.getMethodBinding();
    JavaMethod md = JavaMethod.getJavaMethod(binding);
    String key = md.getKey();
    IOSMethod iosMethod = methodMappings.get(key);
    if (iosMethod != null) {
      assert !node.hasRetainedResult();
      if (key.equals("java.lang.String.String(Ljava/lang/String;)V")) {
        // Special case: replace new String(constant) to constant (avoid clang warning).
        Expression arg = node.getArguments().get(0);
        if (arg instanceof StringLiteral) {
          node.replaceWith(arg.copy());
          return false;
        }
      }
      IOSMethodBinding methodBinding = IOSMethodBinding.newMappedMethod(iosMethod, binding);
      MethodInvocation newInvocation = new MethodInvocation(methodBinding,
          new SimpleName(Types.resolveIOSType(iosMethod.getDeclaringClass())));

      // Set parameters.
      copyInvocationArguments(null, node.getArguments(), newInvocation.getArguments());

      node.replaceWith(newInvocation);
    }
    return true;
  }

  @Override
  public void endVisit(TypeDeclaration node) {
    // If this type implements Cloneable but its parent doesn't, add a
    // copyWithZone: method that calls clone().
    ITypeBinding type = node.getTypeBinding();
    if (type.isAssignmentCompatible(javaLangCloneable)) {
      ITypeBinding superclass = type.getSuperclass();
      if (superclass == null || !superclass.isAssignmentCompatible(javaLangCloneable)) {
        addCopyWithZoneMethod(node);
      }
    }
  }

  private void copyInvocationArguments(Expression receiver, List<Expression> oldArgs,
      List<Expression> newArgs) {
    // set the receiver as the first argument
    if (receiver != null) {
      Expression delegate = receiver.copy();
      delegate.accept(this);
      newArgs.add(delegate);
    }

    // copy remaining arguments
    for (Expression oldArg : oldArgs) {
      newArgs.add(oldArg.copy());
    }
  }

  private MethodInvocation makeCloneInvocation(ITypeBinding declaringClass) {
    GeneratedMethodBinding cloneBinding = GeneratedMethodBinding.newMethod(
        "clone", 0, Types.resolveIOSType("NSObject"), declaringClass);
    return new MethodInvocation(cloneBinding, null);
  }

  private void addCopyWithZoneMethod(TypeDeclaration node) {
    // Create copyWithZone: method.
    ITypeBinding type = node.getTypeBinding().getTypeDeclaration();
    IOSMethod iosMethod = IOSMethod.create("id copyWithZone:(NSZone *)zone");
    IOSMethodBinding binding = IOSMethodBinding.newMethod(
        iosMethod, Modifier.PUBLIC, Types.resolveIOSType("id"), type);
    MethodDeclaration cloneMethod = new MethodDeclaration(binding);

    // Add NSZone *zone parameter.
    GeneratedVariableBinding zoneBinding = new GeneratedVariableBinding(
        "zone", 0, Types.resolveIOSType("NSZone"), false, true, binding.getDeclaringClass(),
        binding);
    binding.addParameter(zoneBinding.getType());
    cloneMethod.getParameters().add(new SingleVariableDeclaration(zoneBinding));

    Block block = new Block();
    cloneMethod.setBody(block);

    MethodInvocation cloneInvocation = makeCloneInvocation(type);
    block.getStatements().add(new ReturnStatement(cloneInvocation));

    node.getBodyDeclarations().add(cloneMethod);
  }
}
