// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.actions;

import com.intellij.ide.ui.search.SearchUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurableGroup;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.options.TabbedConfigurable;
import com.intellij.openapi.options.ex.ConfigurableExtensionPointUtil;
import com.intellij.openapi.options.ex.ConfigurableVisitor;
import com.intellij.openapi.options.ex.ConfigurableWrapper;
import com.intellij.openapi.options.newEditor.SettingsDialogFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.navigation.Place;
import com.intellij.util.ui.update.Activatable;
import com.intellij.util.ui.update.UiNotifyConnector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * @author max
 */
public class ShowSettingsUtilImpl extends ShowSettingsUtil {
  private static final Logger LOG = Logger.getInstance("#com.intellij.ide.actions.ShowSettingsUtilImpl");

  @NotNull
  private static Project getProject(@Nullable Project project) {
    return project != null ? project : ProjectManager.getInstance().getDefaultProject();
  }

  @NotNull
  public static DialogWrapper getDialog(@Nullable Project project, @NotNull ConfigurableGroup[] groups, @Nullable Configurable toSelect) {
    project = getProject(project);
    final ConfigurableGroup[] filteredGroups = filterEmptyGroups(groups);
    return SettingsDialogFactory.getInstance().create(project, filteredGroups, toSelect, null);
  }

  /**
   * @param project         a project used to load project settings or {@code null}
   * @param withIdeSettings specifies whether to load application settings or not
   * @return an array with the root configurable group
   */
  @NotNull
  public static ConfigurableGroup[] getConfigurableGroups(@Nullable Project project, boolean withIdeSettings) {
    ConfigurableGroup group = ConfigurableExtensionPointUtil.getConfigurableGroup(project, withIdeSettings);
    return new ConfigurableGroup[]{group};
  }

  /**
   * @param project         a project used to load project settings or {@code null}
   * @param withIdeSettings specifies whether to load application settings or not
   * @return all configurables as a plain list except the root configurable group
   */
  @NotNull
  public static Configurable[] getConfigurables(@Nullable Project project, boolean withIdeSettings) {
    ConfigurableGroup group = ConfigurableExtensionPointUtil.getConfigurableGroup(project, withIdeSettings);
    List<Configurable> list = new ArrayList<>();
    collect(list, group.getConfigurables());
    return list.toArray(new Configurable[0]);
  }

  private static void collect(List<? super Configurable> list, Configurable... configurables) {
    for (Configurable configurable : configurables) {
      list.add(configurable);
      if (configurable instanceof Configurable.Composite) {
        Configurable.Composite composite = (Configurable.Composite)configurable;
        collect(list, composite.getConfigurables());
      }
    }
  }

  @Override
  public void showSettingsDialog(@NotNull Project project, @NotNull ConfigurableGroup... group) {
    try {
      getDialog(project, group, null).show();
    }
    catch (Exception e) {
      LOG.error(e);
    }
  }

  @Override
  public void showSettingsDialog(@Nullable final Project project, final Class configurableClass) {
    //noinspection unchecked
    showSettingsDialog(project, configurableClass, null);
  }

  @Override
  public <T extends Configurable> void showSettingsDialog(@Nullable Project project,
                                                          @NotNull Class<T> configurableClass,
                                                          @Nullable Consumer<T> additionalConfiguration) {
    assert Configurable.class.isAssignableFrom(configurableClass) : "Not a configurable: " + configurableClass.getName();
    showSettingsDialog(project, it -> ConfigurableWrapper.cast(configurableClass, it) != null, it -> {
      if (additionalConfiguration != null) {
        T toConfigure = ConfigurableWrapper.cast(configurableClass, it);
        assert toConfigure != null : "Wrong configurable found: " + it.getClass();
        additionalConfiguration.accept(toConfigure);
      }
    });
  }

  @Override
  public void showSettingsDialog(@Nullable Project project,
                                 @NotNull Predicate<? super Configurable> predicate,
                                 @Nullable Consumer<? super Configurable> additionalConfiguration) {
    ConfigurableGroup[] groups = getConfigurableGroups(project, true);
    Configurable config = new ConfigurableVisitor() {
      @Override
      protected boolean accept(Configurable configurable) {
        return predicate.test(configurable);
      }
    }.find(groups);

    assert config != null : "Cannot find configurable for specified predicate";

    if (additionalConfiguration != null) {
      additionalConfiguration.accept(config);
    }

    getDialog(project, groups, config).show();
  }

  @Override
  public void showSettingsDialog(@Nullable final Project project, @NotNull final String nameToSelect) {
    ConfigurableGroup[] groups = getConfigurableGroups(project, true);
    Project actualProject = getProject(project);

    groups = filterEmptyGroups(groups);
    getDialog(actualProject, groups, findPreselectedByDisplayName(nameToSelect, groups)).show();
  }

  @Nullable
  private static Configurable findPreselectedByDisplayName(final String preselectedConfigurableDisplayName, ConfigurableGroup[] groups) {
    final List<Configurable> all = SearchUtil.expand(groups);
    for (Configurable each : all) {
      if (preselectedConfigurableDisplayName.equals(each.getDisplayName())) return each;
    }
    return null;
  }

  public static void showSettingsDialog(@Nullable Project project, final String id2Select, final String filter) {
    ConfigurableGroup[] group = getConfigurableGroups(project, true);

    group = filterEmptyGroups(group);
    final Configurable configurable2Select = id2Select == null ? null : new ConfigurableVisitor.ByID(id2Select).find(group);

    SettingsDialogFactory.getInstance().create(getProject(project), group, configurable2Select, filter).show();
  }

  @Override
  public void showSettingsDialog(@NotNull final Project project, final Configurable toSelect) {
    getDialog(project, getConfigurableGroups(project, true), toSelect).show();
  }

  @NotNull
  private static ConfigurableGroup[] filterEmptyGroups(@NotNull final ConfigurableGroup[] group) {
    List<ConfigurableGroup> groups = new ArrayList<>();
    for (ConfigurableGroup g : group) {
      if (g.getConfigurables().length > 0) {
        groups.add(g);
      }
    }
    return groups.toArray(new ConfigurableGroup[0]);
  }

  @Override
  public boolean editConfigurable(Project project, Configurable configurable) {
    return editConfigurable(project, createDimensionKey(configurable), configurable);
  }

  @Override
  public <T extends Configurable> T findProjectConfigurable(final Project project, final Class<T> confClass) {
    return ConfigurableExtensionPointUtil.findProjectConfigurable(project, confClass);
  }

  @Override
  public boolean editConfigurable(Project project, String dimensionServiceKey, @NotNull Configurable configurable) {
    return editConfigurable(project, dimensionServiceKey, configurable, isWorthToShowApplyButton(configurable));
  }

  private static boolean isWorthToShowApplyButton(@NotNull Configurable configurable) {
    return configurable instanceof Place.Navigator ||
           configurable instanceof Composite ||
           configurable instanceof TabbedConfigurable;
  }

  @Override
  public boolean editConfigurable(Project project, String dimensionServiceKey, @NotNull Configurable configurable, boolean showApplyButton) {
    return editConfigurable(null, project, configurable, dimensionServiceKey, null, showApplyButton);
  }

  @Override
  public boolean editConfigurable(Project project, Configurable configurable, Runnable advancedInitialization) {
    return editConfigurable(null, project, configurable, createDimensionKey(configurable), advancedInitialization, isWorthToShowApplyButton(configurable));
  }

  @Override
  public boolean editConfigurable(@Nullable Component parent, @NotNull Configurable configurable) {
    return editConfigurable(parent, configurable, null);
  }

  @Override
  public boolean editConfigurable(@Nullable Component parent, @NotNull Configurable configurable, @Nullable Runnable advancedInitialization) {
    return editConfigurable(parent, null, configurable, createDimensionKey(configurable), advancedInitialization, isWorthToShowApplyButton(configurable));
  }

  private static boolean editConfigurable(@Nullable Component parent,
                                          @Nullable Project project,
                                          @NotNull Configurable configurable,
                                          String dimensionKey,
                                          @Nullable final Runnable advancedInitialization,
                                          boolean showApplyButton) {
    final DialogWrapper editor;
    if (parent == null) {
      editor = SettingsDialogFactory.getInstance().create(project, dimensionKey, configurable, showApplyButton, false);
    }
    else {
      editor = SettingsDialogFactory.getInstance().create(parent, dimensionKey, configurable, showApplyButton, false);
    }
    if (advancedInitialization != null) {
      new UiNotifyConnector.Once(editor.getContentPane(), new Activatable.Adapter() {
        @Override
        public void showNotify() {
          advancedInitialization.run();
        }
      });
    }
    return editor.showAndGet();
  }

  @NotNull
  public static String createDimensionKey(@NotNull Configurable configurable) {
    return '#' + configurable.getDisplayName().replace('\n', '_').replace(' ', '_');
  }

  @Override
  public boolean editConfigurable(Component parent, String dimensionServiceKey, Configurable configurable) {
    return editConfigurable(parent, null, configurable, dimensionServiceKey, null, isWorthToShowApplyButton(configurable));
  }
}
