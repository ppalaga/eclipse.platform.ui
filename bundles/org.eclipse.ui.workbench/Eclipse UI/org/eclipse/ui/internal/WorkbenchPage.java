/*******************************************************************************
 * Copyright (c) 2000, 2010 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.ui.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.annotation.PostConstruct;
import javax.inject.Inject;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.ListenerList;
import org.eclipse.core.runtime.dynamichelpers.IExtensionTracker;
import org.eclipse.e4.core.contexts.IContextConstants;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.core.services.events.IEventBroker;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.descriptor.basic.MPartDescriptor;
import org.eclipse.e4.ui.model.application.ui.MElementContainer;
import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.advanced.MPerspective;
import org.eclipse.e4.ui.model.application.ui.advanced.MPerspectiveStack;
import org.eclipse.e4.ui.model.application.ui.advanced.MPlaceholder;
import org.eclipse.e4.ui.model.application.ui.advanced.impl.AdvancedFactoryImpl;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.model.application.ui.basic.MPartStack;
import org.eclipse.e4.ui.model.application.ui.basic.MStackElement;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.e4.ui.model.application.ui.basic.MWindowElement;
import org.eclipse.e4.ui.services.EContextService;
import org.eclipse.e4.ui.workbench.UIEvents;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.e4.ui.workbench.modeling.EPartService;
import org.eclipse.e4.ui.workbench.modeling.EPartService.PartState;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.custom.BusyIndicator;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IEditorDescriptor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorLauncher;
import org.eclipse.ui.IEditorMatchingStrategy;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IEditorRegistry;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.INavigationHistory;
import org.eclipse.ui.IPartListener;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IPathEditorInput;
import org.eclipse.ui.IPerspectiveDescriptor;
import org.eclipse.ui.IPerspectiveFactory;
import org.eclipse.ui.IPerspectiveRegistry;
import org.eclipse.ui.IReusableEditor;
import org.eclipse.ui.ISaveablePart;
import org.eclipse.ui.ISaveablesLifecycleListener;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IShowEditorInput;
import org.eclipse.ui.ISources;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWorkingSet;
import org.eclipse.ui.IWorkingSetManager;
import org.eclipse.ui.MultiPartInitException;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.WorkbenchException;
import org.eclipse.ui.internal.e4.compatibility.CompatibilityEditor;
import org.eclipse.ui.internal.e4.compatibility.CompatibilityPart;
import org.eclipse.ui.internal.e4.compatibility.CompatibilityView;
import org.eclipse.ui.internal.e4.compatibility.E4Util;
import org.eclipse.ui.internal.e4.compatibility.ModeledPageLayout;
import org.eclipse.ui.internal.misc.ExternalEditor;
import org.eclipse.ui.internal.misc.UIListenerLogging;
import org.eclipse.ui.internal.registry.EditorDescriptor;
import org.eclipse.ui.internal.registry.IActionSetDescriptor;
import org.eclipse.ui.internal.registry.IWorkbenchRegistryConstants;
import org.eclipse.ui.internal.registry.PerspectiveDescriptor;
import org.eclipse.ui.internal.registry.UIExtensionTracker;
import org.eclipse.ui.internal.registry.ViewDescriptor;
import org.eclipse.ui.internal.util.Util;
import org.eclipse.ui.model.IWorkbenchAdapter;
import org.eclipse.ui.part.IShowInSource;
import org.eclipse.ui.part.ShowInContext;
import org.eclipse.ui.views.IViewDescriptor;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;

/**
 * A collection of views and editors in a workbench.
 */
public class WorkbenchPage extends CompatibleWorkbenchPage implements
        IWorkbenchPage {
	
	static final String SECONDARY_ID_HEADER = "3x-secondary:"; //$NON-NLS-1$

	class E4PartListener implements org.eclipse.e4.ui.workbench.modeling.IPartListener {

		public void partActivated(MPart part) {
			updateActivations(part);
			firePartActivated(part);
		}

		public void partBroughtToTop(MPart part) {
			updateBroughtToTop(part);
			firePartBroughtToTop(part);
		}

		public void partDeactivated(MPart part) {
			firePartDeactivated(part);

			Object client = part.getObject();
			if (client instanceof CompatibilityPart) {
				PartSite site = (PartSite) ((CompatibilityPart) client).getPart().getSite();
				site.deactivateActionBars(site instanceof ViewSite);
			}

			((WorkbenchWindow) getWorkbenchWindow()).getStatusLineManager().update(false);
		}

		public void partHidden(MPart part) {
			firePartHidden(part);
		}

		public void partVisible(MPart part) {
			firePartVisible(part);
		}
	}

	ArrayList<MPart> activationList = new ArrayList<MPart>();

	private void updateActivations(MPart part) {
		activationList.remove(part);
		activationList.add(0, part);
		updateActivePartSources(part);
		updateActiveEditorSources(part);
		updateShowInSources(part);

		Object client = part.getObject();
		if (client instanceof CompatibilityPart) {
			IWorkbenchPart workbenchPart = ((CompatibilityPart) client).getPart();
			PartSite site = (PartSite) workbenchPart.getSite();
			site.activateActionBars(true);

			IActionBars actionBars = site.getActionBars();
			if (actionBars instanceof EditorActionBars) {
				((EditorActionBars) actionBars).partChanged(workbenchPart);
			}
		}

		((WorkbenchWindow) getWorkbenchWindow()).getStatusLineManager().update(false);

		IWorkbenchPart workbenchPart = getWorkbenchPart(part);
		if (workbenchPart instanceof IEditorPart) {
			navigationHistory.markEditor((IEditorPart) workbenchPart);
		}
	}

	private void updateActivePartSources(MPart part) {
		IWorkbenchPart workbenchPart = getWorkbenchPart(part);
		if (workbenchPart == null) {
			window.getContext().set(ISources.ACTIVE_PART_NAME, null);
			window.getContext().set(ISources.ACTIVE_PART_ID_NAME, null);
			window.getContext().set(ISources.ACTIVE_SITE_NAME, null);

			if (application.getContext().get(IContextConstants.ACTIVE_CHILD) == window.getContext()) {
				application.getContext().set(ISources.ACTIVE_PART_NAME, null);
				application.getContext().set(ISources.ACTIVE_PART_ID_NAME, null);
				application.getContext().set(ISources.ACTIVE_SITE_NAME, null);
			}
		} else {
			window.getContext().set(ISources.ACTIVE_PART_NAME, workbenchPart);
			window.getContext().set(ISources.ACTIVE_PART_ID_NAME, workbenchPart.getSite().getId());
			window.getContext().set(ISources.ACTIVE_SITE_NAME, workbenchPart.getSite());

			if (application.getContext().get(IContextConstants.ACTIVE_CHILD) == window.getContext()) {
				application.getContext().set(ISources.ACTIVE_PART_NAME, workbenchPart);
				application.getContext().set(ISources.ACTIVE_PART_ID_NAME,
						workbenchPart.getSite().getId());
				application.getContext().set(ISources.ACTIVE_SITE_NAME, workbenchPart.getSite());
			}
		}

	}

	private IWorkbenchPart getWorkbenchPart(MPart part) {
		if (part != null) {
			Object clientObject = part.getObject();
			if (clientObject instanceof CompatibilityPart) {
				return ((CompatibilityPart) clientObject).getPart();
			}
		}
		return null;
	}

	private void updateActiveEditorSources(MPart part) {
		IEditorPart editor = getEditor(part);
		window.getContext().set(ISources.ACTIVE_EDITOR_ID_NAME,
				editor == null ? null : editor.getSite().getId());
		window.getContext().set(ISources.ACTIVE_EDITOR_NAME, editor);
		window.getContext().set(ISources.ACTIVE_EDITOR_INPUT_NAME,
				editor == null ? null : editor.getEditorInput());

		if (application.getContext().get(IContextConstants.ACTIVE_CHILD) == window.getContext()) {
			application.getContext().set(ISources.ACTIVE_EDITOR_ID_NAME,
					editor == null ? null : editor.getSite().getId());
			application.getContext().set(ISources.ACTIVE_EDITOR_NAME, editor);
			application.getContext().set(ISources.ACTIVE_EDITOR_INPUT_NAME,
					editor == null ? null : editor.getEditorInput());
		}
	}

	private void updateShowInSources(MPart part) {
		IWorkbenchPart workbenchPart = getWorkbenchPart(part);
		ShowInContext context = getContext(workbenchPart);
		if (context != null) {
			window.getContext().set(ISources.SHOW_IN_INPUT, context.getInput());
			window.getContext().set(ISources.SHOW_IN_SELECTION, context.getSelection());

			if (application.getContext().get(IContextConstants.ACTIVE_CHILD) == window.getContext()) {
				application.getContext().set(ISources.SHOW_IN_INPUT, context.getInput());
				application.getContext().set(ISources.SHOW_IN_SELECTION, context.getSelection());
			}
		}
	}

	private IShowInSource getShowInSource(IWorkbenchPart sourcePart) {
		return (IShowInSource) Util.getAdapter(sourcePart, IShowInSource.class);
	}

	private ShowInContext getContext(IWorkbenchPart sourcePart) {
		IShowInSource source = getShowInSource(sourcePart);
		if (source != null) {
			ShowInContext context = source.getShowInContext();
			if (context != null) {
				return context;
			}
		} else if (sourcePart instanceof IEditorPart) {
			Object input = ((IEditorPart) sourcePart).getEditorInput();
			ISelectionProvider sp = sourcePart.getSite().getSelectionProvider();
			ISelection sel = sp == null ? null : sp.getSelection();
			return new ShowInContext(input, sel);
		}
		return null;
	}

	private IEditorPart getEditor(MPart part) {
		if (part != null) {
			Object clientObject = part.getObject();
			if (clientObject instanceof CompatibilityEditor) {
				return ((CompatibilityEditor) clientObject).getEditor();
			}
		}
		return getActiveEditor();
	}

	private void updateBroughtToTop(MPart part) {
		MElementContainer<?> parent = part.getParent();
		if (parent instanceof MPartStack) {
			int newIndex = lastIndexOfContainer(parent);
			// New index can be -1 if there is no last index
			if (newIndex >= 0 && part == activationList.get(newIndex)) {
				return;
			}
			activationList.remove(part);
			if (newIndex >= 0 && newIndex < activationList.size() - 1) {
				activationList.add(newIndex, part);
			} else {
				activationList.add(part);
			}
		}
	}

	private int lastIndexOfContainer(MElementContainer<?> parent) {
		for (int i = 0; i < activationList.size(); i++) {
			MPart mPart = activationList.get(i);
			if (mPart.getParent() == parent) {
				return i;
			}
		}
		return -1;
	}

	private List<ViewReference> viewReferences = new ArrayList<ViewReference>();
	private List<EditorReference> editorReferences = new ArrayList<EditorReference>();

	private List<IPerspectiveDescriptor> sortedPerspectives = new ArrayList<IPerspectiveDescriptor>();

	private ListenerList partListenerList = new ListenerList();
	private ListenerList partListener2List = new ListenerList();
	private IPerspectiveDescriptor perspective;


	private E4PartListener e4PartListener = new E4PartListener();

	protected WorkbenchWindow legacyWindow;

    private IAdaptable input;

    private IWorkingSet workingSet;
    
    private AggregateWorkingSet aggregateWorkingSet;

    private Composite composite;
    


    private ListenerList propertyChangeListeners = new ListenerList();

    private IActionBars actionBars;
    
    private ActionSetManager actionSets;
    

    private NavigationHistory navigationHistory = new NavigationHistory(this);
    

    /**
     * If we're in the process of activating a part, this points to the new part.
     * Otherwise, this is null.
     */
    private IWorkbenchPartReference partBeingActivated = null;
    
    
    
    private IPropertyChangeListener workingSetPropertyChangeListener = new IPropertyChangeListener() {
        /*
         * Remove the working set from the page if the working set is deleted.
         */
        public void propertyChange(PropertyChangeEvent event) {
            String property = event.getProperty();
            if (IWorkingSetManager.CHANGE_WORKING_SET_REMOVE.equals(property)) {
            		if(event.getOldValue().equals(workingSet)) {
						setWorkingSet(null);
					}
            		
            		// room for optimization here
            		List newList = new ArrayList(Arrays.asList(workingSets));
            		if (newList.remove(event.getOldValue())) {
						setWorkingSets((IWorkingSet []) newList
								.toArray(new IWorkingSet [newList.size()]));
					}
            }
        }
    };


	private IExtensionTracker tracker;
    
    // Deferral count... delays disposing parts and sending certain events if nonzero
    private int deferCount = 0;

    
	private IWorkingSet[] workingSets = new IWorkingSet[0];
	private String aggregateWorkingSetId;

	@Inject
	private EPartService partService;

	@Inject
	private MApplication application;

	@Inject
	private MWindow window;

	@Inject
	private EModelService modelService;

	@Inject
	private IEventBroker broker;


    /**
	 * Constructs a new page with a given perspective and input.
	 * 
	 * @param w
	 *            the parent window
	 * @param layoutID
	 *            must not be <code>null</code>
	 * @param input
	 *            the page input
	 * @throws WorkbenchException
	 *             on null layout id
	 */
    public WorkbenchPage(WorkbenchWindow w, String layoutID, IAdaptable input)
            throws WorkbenchException {
        super();
        if (layoutID == null) {
			throw new WorkbenchException(WorkbenchMessages.WorkbenchPage_UndefinedPerspective);
		}
        init(w, layoutID, input, true);
    }

    /**
     * Constructs a page. <code>restoreState(IMemento)</code> should be
     * called to restore this page from data stored in a persistance file.
     * 
     * @param w
     *            the parent window
     * @param input
     *            the page input
     * @throws WorkbenchException 
     */
    public WorkbenchPage(WorkbenchWindow w, IAdaptable input)
            throws WorkbenchException {
        super();
        init(w, null, input, false);
    }

    /**
     * Allow access to the UI model that this page is managing
     * @return the MWindow element for this page
     */
    public MWindow getWindowModel() {
    	return window;
    	
    }
    /**
     * Activates a part. The part will be brought to the front and given focus.
     * 
     * @param part
     *            the part to activate
     */
    public void activate(IWorkbenchPart part) {
		if (part == null || !certifyPart(part) || legacyWindow.isClosing()) {
			return;
		}
		MPart mpart = findPart(part);
		if (mpart != null) {
			if (CompatibilityPart.COMPATIBILITY_EDITOR_URI.equals(mpart.getContributionURI())) {
				for (MPart localPart : partService.getParts()) {
					if (localPart == mpart) {
						// this editor is actually in this perspective, just
						// activate it
						partService.activate(mpart);
						return;
					}
				}

				// we may get here when an editor's reference is found even
				// though it does not actually exist in this perspective (that
				// is, it has been detached from the editor area and is in
				// another perspective's view stack), in this scenario, we want
				// to open an editor with the same input/id in the current
				// perspective, we use MATCH_NONE to ensure that we don't pick
				// up the extra editor reference
				try {
					IEditorPart editor = (IEditorPart) part;
					openEditor(editor.getEditorInput(), editor.getSite().getId(), true, MATCH_NONE);
				} catch (PartInitException e) {
					WorkbenchPlugin.log(e);
				}
			} else {
				// we show the view instead of simply activating it, this is
				// because we do not currently clear out our view references, so
				// we may find a part that is not not actually visible in the
				// current perspective so we have to force a show, see bug
				// 315133 and bug 320327
				String secondaryId = null;
				for (String tag : mpart.getTags()) {
					if (tag != null && tag.startsWith(SECONDARY_ID_HEADER)) {
						secondaryId = tag.substring(SECONDARY_ID_HEADER.length());
					}
				}

				try {
					showView(mpart.getElementId(), secondaryId, VIEW_ACTIVATE);
				} catch (PartInitException e) {
					WorkbenchPlugin.log(e);
				}
			}
		}
	}

    /**
	 * Adds an IPartListener to the part service.
	 */
    public void addPartListener(IPartListener l) {
		partListenerList.add(l);
    }

	/**
	 * Adds an IPartListener to the part service.
	 */
    public void addPartListener(IPartListener2 l) {
		partListener2List.add(l);
    }

    /**
     * Implements IWorkbenchPage
     * 
     * @see org.eclipse.ui.IWorkbenchPage#addPropertyChangeListener(IPropertyChangeListener)
     * @since 2.0
     * @deprecated individual views should store a working set if needed and
     *             register a property change listener directly with the
     *             working set manager to receive notification when the view
     *             working set is removed.
     */
    public void addPropertyChangeListener(IPropertyChangeListener listener) {
        propertyChangeListeners.add(listener);
    }

    /*
     * (non-Javadoc) Method declared on ISelectionListener.
     */
    public void addSelectionListener(ISelectionListener listener) {
		getWorkbenchWindow().getSelectionService().addSelectionListener(listener);
    }

    /*
     * (non-Javadoc) Method declared on ISelectionListener.
     */
    public void addSelectionListener(String partId, ISelectionListener listener) {
		getWorkbenchWindow().getSelectionService().addSelectionListener(partId, listener);
    }

    /*
     * (non-Javadoc) Method declared on ISelectionListener.
     */
    public void addPostSelectionListener(ISelectionListener listener) {
		getWorkbenchWindow().getSelectionService().addPostSelectionListener(listener);
    }

    /*
     * (non-Javadoc) Method declared on ISelectionListener.
     */
    public void addPostSelectionListener(String partId,
            ISelectionListener listener) {
		getWorkbenchWindow().getSelectionService().addPostSelectionListener(partId, listener);
    }
    
    /**
     * Moves a part forward in the Z order of a perspective so it is visible.
     * If the part is in the same stack as the active part, the new part is
     * activated.
     * 
     * @param part
     *            the part to bring to move forward
     */
    public void bringToTop(IWorkbenchPart part) {
        // Sanity check.
		MPart mpart = findPart(part);
		if (mpart != null) {
			partService.bringToTop(mpart);
		}
    }

	public MPart findPart(IWorkbenchPart part) {
		if (part == null) {
			return null;
		}

		for (Iterator<ViewReference> it = viewReferences.iterator(); it.hasNext();) {
			IViewReference reference = it.next();
			if (part == reference.getPart(false)) {
				return ((WorkbenchPartReference) reference).getModel();
			}
		}

		for (Iterator<EditorReference> it = editorReferences.iterator(); it.hasNext();) {
			IEditorReference reference = it.next();
			if (part == reference.getPart(false)) {
				return ((WorkbenchPartReference) reference).getModel();
			}
		}
		return null;
	}

	public void createEditorReferenceForPart(final MPart part, IEditorInput input, String editorId) {
		IEditorRegistry registry = legacyWindow.getWorkbench().getEditorRegistry();
		EditorDescriptor descriptor = (EditorDescriptor) registry.findEditor(editorId);
		final EditorReference ref = new EditorReference(window.getContext(), this, part, input,
				descriptor);
		editorReferences.add(ref);
		final IEventBroker broker = (IEventBroker) application.getContext().get(
				IEventBroker.class.getName());
		broker.subscribe(UIEvents.buildTopic(UIEvents.Context.TOPIC, UIEvents.Context.CONTEXT),
				new EventHandler() {
					public void handleEvent(Event event) {
						Object element = event.getProperty(UIEvents.EventTags.ELEMENT);
						if (element == part) {
							if (part.getContext() != null) {
								broker.unsubscribe(this);
								part.getContext().set(EditorReference.class.getName(), ref);
							}
						}
					}
				});
	}


	public List<EditorReference> getInternalEditorReferences() {
		return editorReferences;
	}

	public EditorReference getEditorReference(MPart part) {
		for (EditorReference ref : editorReferences) {
			if (ref.getModel() == part) {
				return ref;
			}
		}
		return null;
	}

	public ViewReference getViewReference(MPart part) {
		for (IViewReference ref : viewReferences) {
			if (((ViewReference) ref).getModel() == part) {
				return (ViewReference) ref;
			}
		}
		return null;
	}

	public void addViewReference(ViewReference viewReference) {
		viewReferences.add(viewReference);
	}

	public void addEditorReference(EditorReference editorReference) {
		editorReferences.add(editorReference);
	}

	MPartDescriptor findDescriptor(String id) {
		for (MPartDescriptor descriptor : application.getDescriptors()) {
			if (descriptor.getElementId().equals(id)) {
				return descriptor;
			}
		}
		return null;
	}

	MPart findPart(String viewId, String secondaryId) {
		if (secondaryId == null) {
			Collection<MPart> parts = partService.getParts();
			partsLoop: for (MPart part : parts) {
				if (part.getElementId().equals(viewId)) {
					for (String tag : part.getTags()) {
						if (tag.startsWith(SECONDARY_ID_HEADER)) {
							continue partsLoop;
						}
					}

					return part;
				}
			}
		}

		Collection<MPart> parts = partService.getParts();
		for (MPart part : parts) {
			if (part.getElementId().equals(viewId) && part.getTags().contains(secondaryId)) {
				return part;
			}
		}
		return null;
	}

	PartState convert(int mode) {
		switch (mode) {
		case VIEW_ACTIVATE:
			return PartState.ACTIVATE;
		case VIEW_VISIBLE:
			return PartState.VISIBLE;
		case VIEW_CREATE:
			return PartState.CREATE;
		}
		throw new IllegalArgumentException(WorkbenchMessages.WorkbenchPage_IllegalViewMode);
	}

    /**
     * Shows a view.
     * 
     * Assumes that a busy cursor is active.
     */
	protected IViewPart busyShowView(String viewId, String secondaryId, int mode)
            throws PartInitException {
    	switch (mode) {
		case VIEW_ACTIVATE:
		case VIEW_VISIBLE:
		case VIEW_CREATE:
			break;
		default:
			throw new IllegalArgumentException(WorkbenchMessages.WorkbenchPage_IllegalViewMode);
		}

		if (secondaryId != null) {
			if (secondaryId.length() == 0 || secondaryId.indexOf(':') != -1) {
				throw new IllegalArgumentException(
						WorkbenchMessages.WorkbenchPage_IllegalSecondaryId);
			}

			secondaryId = SECONDARY_ID_HEADER + secondaryId;

			MPartDescriptor descriptor = findDescriptor(viewId);
			if (!descriptor.isAllowMultiple()) {
				throw new PartInitException(NLS.bind(WorkbenchMessages.ViewFactory_noMultiple,
						viewId));
			}
		}

		MPart part = findPart(viewId, secondaryId);
		if (part == null) {
			MPlaceholder ph = partService.createSharedPart(viewId, window, secondaryId != null);
			part = (MPart) ph.getRef();
			part.setCurSharedRef(ph);

			if (ph == null) {
				throw new PartInitException(NLS.bind(WorkbenchMessages.ViewFactory_couldNotCreate,
						viewId));
			}

			if (secondaryId != null) {
				part.getTags().add(secondaryId);
			}

			partService.showPart(part, convert(mode));

			CompatibilityView compatibilityView = (CompatibilityView) part.getObject();

			if (compatibilityView != null) {
				IWorkbenchPartReference ref = compatibilityView.getReference();

				legacyWindow.firePerspectiveChanged(this, getPerspective(), ref, CHANGE_VIEW_SHOW);
				legacyWindow.firePerspectiveChanged(this, getPerspective(), CHANGE_VIEW_SHOW);
			}
			return compatibilityView.getView();
		}

		part = partService.showPart(part, convert(mode));
		if (secondaryId != null) {
			part.getTags().add(secondaryId);
		}

		CompatibilityView compatibilityView = (CompatibilityView) part.getObject();

		if (mode == VIEW_ACTIVATE) {
			compatibilityView.delegateSetFocus();
		}

		if (compatibilityView != null) {
			IWorkbenchPartReference ref = compatibilityView.getReference();

			legacyWindow.firePerspectiveChanged(this, getPerspective(), ref, CHANGE_VIEW_SHOW);
			legacyWindow.firePerspectiveChanged(this, getPerspective(), CHANGE_VIEW_SHOW);
		}
		return compatibilityView.getView();
        
    }

    /**
     * Returns whether a part exists in the current page.
     */
    private boolean certifyPart(IWorkbenchPart part) {
        //Workaround for bug 22325
        if (part != null && !(part.getSite() instanceof PartSite)) {
			return false;
		}
		return true;
    }

    /**
     * Closes the perspective.
     */
    public boolean close() {
        final boolean[] ret = new boolean[1];
        BusyIndicator.showWhile(null, new Runnable() {
            public void run() {
				// ret[0] = legacyWindow.closePage(WorkbenchPage.this, true);
				ret[0] = true;
            }
        });
        return ret[0];
    }

    /**
     * See IWorkbenchPage
     */
    public boolean closeAllSavedEditors() {
        // get the Saved editors
        IEditorReference editors[] = getEditorReferences();
        IEditorReference savedEditors[] = new IEditorReference[editors.length];
        int j = 0;
        for (int i = 0; i < editors.length; i++) {
            IEditorReference editor = editors[i];
            if (!editor.isDirty()) {
                savedEditors[j++] = editor;
            }
        }
        //there are no unsaved editors
        if (j == 0) {
			return true;
		}
        IEditorReference[] newSaved = new IEditorReference[j];
        System.arraycopy(savedEditors, 0, newSaved, 0, j);
        return closeEditors(newSaved, false);
    }

    /**
     * See IWorkbenchPage
     */
    public boolean closeAllEditors(boolean save) {
        return closeEditors(getEditorReferences(), save);
    }

	/**
	 * See IWorkbenchPage
	 */
	public boolean closeEditors(IEditorReference[] refArray, boolean save) {
		if (refArray.length == 0) {
			return true;
        }
        
		// Check if we're being asked to close any parts that are already closed
		// or cannot
		// be closed at this time
		ArrayList toClose = new ArrayList();
		for (int i = 0; i < refArray.length; i++) {
			IEditorReference reference = refArray[i];

			// If we're in the middle of creating this part, this is a
			// programming error. Abort the entire
			// close operation. This usually occurs if someone tries to open a
			// dialog in a method that
			// isn't allowed to do so, and a *syncExec tries to close the part.
			// If this shows up in a log
			// file with a dialog's event loop on the stack, then the code that
			// opened the dialog is usually
			// at fault.
			if (reference == partBeingActivated) {
				WorkbenchPlugin.log(new RuntimeException(
						"WARNING: Blocked recursive attempt to close part " //$NON-NLS-1$
								+ partBeingActivated.getId()
								+ " while still in the middle of activating it")); //$NON-NLS-1$
				return false;
			}

			if (reference instanceof WorkbenchPartReference) {
				WorkbenchPartReference ref = (WorkbenchPartReference) reference;

				// If we're being asked to close a part that is disposed (ie:
				// already closed),
				// skip it and proceed with closing the remaining parts.
				if (ref.isDisposed()) {
					continue;
                }
            }

			toClose.add(reference);
        }
        
		IEditorReference[] editorRefs = (IEditorReference[]) toClose
				.toArray(new IEditorReference[toClose.size()]);

		// if active navigation position belongs to an editor being closed,
		// update it
		// (The navigation position for an editor N was updated as an editor N +
		// 1
		// was activated. As a result, all but the last editor have up-to-date
		// navigation positions.)
		for (int i = 0; i < editorRefs.length; i++) {
			IEditorReference ref = editorRefs[i];
			if (ref == null)
				continue;
			IEditorPart oldPart = ref.getEditor(false);
			if (oldPart == null)
				continue;
			if (navigationHistory.updateActive(oldPart))
				break; // updated - skip the rest
        }

		// notify the model manager before the close
		List partsToClose = new ArrayList();
		for (int i = 0; i < editorRefs.length; i++) {
			IEditorPart refPart = editorRefs[i].getEditor(false);
			if (refPart != null) {
				partsToClose.add(refPart);
            }
        }

		boolean confirm = true;
		SaveablesList modelManager = null;
		Object postCloseInfo = null;
		if (partsToClose.size() > 0) {
			modelManager = (SaveablesList) getWorkbenchWindow().getService(
					ISaveablesLifecycleListener.class);
			// this may prompt for saving and return null if the user canceled:
			postCloseInfo = modelManager.preCloseParts(partsToClose, save, getWorkbenchWindow());
			if (postCloseInfo == null) {
				return false;
			}
			confirm = false;
        }

		// Fire pre-removal changes
		for (int i = 0; i < editorRefs.length; i++) {
			IEditorReference ref = editorRefs[i];

			// Notify interested listeners before the close
			legacyWindow.firePerspectiveChanged(this, getPerspective(), ref, CHANGE_EDITOR_CLOSE);

		}
        
        deferUpdates(true);
		try {
			if (modelManager != null) {
				modelManager.postClose(postCloseInfo);
			}

			// Close all editors.
			for (IEditorReference editorRef : editorRefs) {
				MPart model = ((EditorReference) editorRef).getModel();
				// saving should've been handled earlier above
				if (!(hidePart(model, false, confirm, false))) {
					return false;
				}
			}
		} finally {
			deferUpdates(false);
        }

		// Notify interested listeners after the close
		legacyWindow.firePerspectiveChanged(this, getPerspective(), CHANGE_EDITOR_CLOSE);
        
        // Return true on success.
		return true;
    }

	/**
	 * Hides the part from this page (window). If the part corresponds to a 3.x
	 * part, its part reference will also be removed.
	 * <p>
	 * This method merely performs the hide invocations. Clients are recommended
	 * to call {@link #hidePart(MPart, boolean, boolean)} instead.
	 * </p>
	 * 
	 * @param part
	 *            the part that should be hidden
	 */
	private void hidePart(MPart part, boolean force) {
		partService.hidePart(part, force);

		for (Iterator<ViewReference> it = viewReferences.iterator(); it.hasNext();) {
			ViewReference reference = it.next();
			if (reference.getModel() == part) {
				it.remove();
				return;
			}
		}

		for (Iterator<EditorReference> it = editorReferences.iterator(); it.hasNext();) {
			EditorReference reference = it.next();
			if (reference.getModel() == part) {
				it.remove();
				return;
			}
		}
	}

	private boolean hidePart(MPart part, boolean save, boolean confirm, boolean force) {
		if (!partService.getParts().contains(part)) {
			return false;
		}

		Object clientObject = part.getObject();
		if (!(clientObject instanceof CompatibilityPart)) {
			// either not a 3.x part or it's an e4 part, should still hide it
			if (save) {
				// save as necessary
				if (partService.savePart(part, confirm)) {
					hidePart(part, force);
					return true;
				}
				return false;
			}
			hidePart(part, force);
			return true;
		}

		CompatibilityPart compatibilityPart = (CompatibilityPart) clientObject;
		IWorkbenchPart workbenchPart = compatibilityPart.getPart();
		if (save) {
			if (workbenchPart instanceof ISaveablePart) {
				ISaveablePart saveablePart = (ISaveablePart) workbenchPart;
				if (!saveSaveable(saveablePart, confirm, true)) {
					return false;
				}
			}
		}

		IWorkbenchPartReference reference = null;

		for (IViewReference viewRef : viewReferences) {
			if (workbenchPart == viewRef.getPart(false)) {
				reference = viewRef;
				break;
			}
		}

		if (reference != null) {
			partService.hidePart(part, force);
			// viewReferences.remove(reference);
			return true;
		}

		for (IEditorReference viewRef : editorReferences) {
			if (workbenchPart == viewRef.getPart(false)) {
				reference = viewRef;
				break;
			}
		}

		if (reference != null) {
			partService.hidePart(part, force);
			editorReferences.remove(reference);
			return true;
		}

		return false;
	}

	private void hidePart(String id) {
		MPart part = partService.findPart(id);
		if (part != null) {
			hidePart(part, true, true, false);
		}
	}
    
    /**
     * Enables or disables listener notifications. This is used to delay listener notifications until the
     * end of a public method.
     * 
     * @param shouldDefer
     */
    private void deferUpdates(boolean shouldDefer) {
        if (shouldDefer) {
            if (deferCount == 0) {
                startDeferring();
            }
            deferCount++;
        } else {
            deferCount--;
            if (deferCount == 0) {
                handleDeferredEvents();
            }
        }
    }
    
    private void startDeferring() {
		// TODO compat: do we defer events
    }

    private void handleDeferredEvents() {
		// TODO compat: do we handler defered events
    }
    
    /**
     * See IWorkbenchPage#closeEditor
     */
    public boolean closeEditor(IEditorReference editorRef, boolean save) {
        return closeEditors(new IEditorReference[] {editorRef}, save);
    }

    /**
     * See IWorkbenchPage#closeEditor
     */
    public boolean closeEditor(IEditorPart editor, boolean save) {
        IWorkbenchPartReference ref = getReference(editor);
        if (ref instanceof IEditorReference) {
        	return closeEditors(new IEditorReference[] {(IEditorReference) ref}, save);
        }
        return false;
    }

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.IWorkbenchPage#closePerspective(org.eclipse.ui.
	 * IPerspectiveDescriptor, boolean, boolean)
	 */
	public void closePerspective(IPerspectiveDescriptor desc, boolean saveParts, boolean closePage) {
		MPerspectiveStack perspectiveStack = modelService.findElements(window, null,
				MPerspectiveStack.class, null).get(0);
		MPerspective persp = (MPerspective) modelService.find(desc.getId(), window);
		// check to ensure this perspective actually exists in this window
		if (persp != null) {
			if (perspectiveStack.getChildren().size() == 1) {
				closeAllPerspectives(saveParts, closePage);
			} else {
				// Remove from caches
				sortedPerspectives.remove(desc);
				modelService.removePerspectiveModel(persp, window);
			}
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.IWorkbenchPage#closeAllPerspectives(boolean, boolean)
	 */
	public void closeAllPerspectives(boolean saveEditors, boolean closePage) {
		if (saveEditors) {
			if (!saveAllEditors(true, true)) {
				return;
			}
		}

		sortedPerspectives.clear();

		for (MPart part : partService.getParts()) {
			// no save, no confirm, force
			hidePart(part, false, true, true);
		}

		if (closePage) {
			legacyWindow.setActivePage(null);
			partService.removePartListener(e4PartListener);
			broker.unsubscribe(selectionHandler);
		}

		MPerspectiveStack perspectiveStack = modelService.findElements(window, null,
				MPerspectiveStack.class, null).get(0);
		MPerspective perspective = perspectiveStack.getSelectedElement();
		while (perspective != null && !perspectiveStack.getChildren().isEmpty()) {
			modelService.removePerspectiveModel(perspective, window);
			perspective = perspectiveStack.getSelectedElement();
		}

		viewReferences.clear();
		editorReferences.clear();
	}

	/**
	 * Forces all perspectives on the page to zoom out.
	 */
	public void unzoomAllPerspectives() {
		// TODO compat: we have no min/max behaviour
    }



    /**
	 * Cleanup.
	 */
	public void dispose() {

// // Always unzoom
		// if (isZoomed()) {
		// zoomOut();
		// }
		//
		// // makeActiveEditor(null);
		// // makeActive(null);
		//        
		// // Close and dispose the editors.
		// closeAllEditors(false);
		//        
		// // Need to make sure model data is cleaned up when the page is
		// // disposed. Collect all the views on the page and notify the
		// // saveable list of a pre/post close. This will free model data.
		// IWorkbenchPartReference[] partsToClose = getOpenParts();
		// List dirtyParts = new ArrayList(partsToClose.length);
		// for (int i = 0; i < partsToClose.length; i++) {
		// IWorkbenchPart part = partsToClose[i].getPart(false);
		// if (part != null && part instanceof IViewPart) {
		// dirtyParts.add(part);
		// }
		// }
		// SaveablesList saveablesList = (SaveablesList)
		// getWorkbenchWindow().getWorkbench().getService(ISaveablesLifecycleListener.class);
		// Object postCloseInfo = saveablesList.preCloseParts(dirtyParts,
		// false,getWorkbenchWindow());
		// saveablesList.postClose(postCloseInfo);
		//
		// // Get rid of perspectives. This will close the views.
		// Iterator itr = perspList.iterator();
		// while (itr.hasNext()) {
		// Perspective perspective = (Perspective) itr.next();
		// legacyWindow.firePerspectiveClosed(this, perspective.getDesc());
		// perspective.dispose();
		// }
		// perspList = new PerspectiveList();
		//
		// // Capture views.
		// IViewReference refs[] = viewFactory.getViews();
		//
		// if (refs.length > 0) {
		// // Dispose views.
		// for (int i = 0; i < refs.length; i++) {
		// final WorkbenchPartReference ref = (WorkbenchPartReference) refs[i];
		// //partList.removePart(ref);
		// //firePartClosed(refs[i]);
		// Platform.run(new SafeRunnable() {
		// public void run() {
		// // WorkbenchPlugin.log(new Status(IStatus.WARNING,
		// WorkbenchPlugin.PI_WORKBENCH,
		////                                Status.OK, "WorkbenchPage leaked a refcount for view " + ref.getId(), null));  //$NON-NLS-1$//$NON-NLS-2$
		//                        
		// ref.dispose();
		// }
		//    
		// public void handleException(Throwable e) {
		// }
		// });
		// }
		// }
		//        
		// activationList = new ActivationList();
		//
		// // Get rid of editor presentation.
		// editorPresentation.dispose();
		//
		// // Get rid of composite.
		// composite.dispose();
		//
		// navigationHistory.dispose();
		//
		// stickyViewMan.clear();
		//        
		// if (tracker != null) {
		// tracker.close();
		// }
		//        
		// // if we're destroying a window in a non-shutdown situation then we
		// should
		// // clean up the working set we made.
		// if (!legacyWindow.getWorkbench().isClosing()) {
		// if (aggregateWorkingSet != null) {
		// PlatformUI.getWorkbench().getWorkingSetManager().removeWorkingSet(aggregateWorkingSet);
		// }
		// }
    }



    /**
     * @return NavigationHistory
     */
    public INavigationHistory getNavigationHistory() {
        return navigationHistory;
    }

    /**
     * Edits the action sets.
     */
    public boolean editActionSets() {
		// Perspective persp = getActivePerspective();
		// if (persp == null) {
		// return false;
		// }
		//
		// // Create list dialog.
		// CustomizePerspectiveDialog dlg =
		// legacyWindow.createCustomizePerspectiveDialog(persp);
		//        
		// // Open.
		// boolean ret = (dlg.open() == Window.OK);
		// if (ret) {
		// legacyWindow.updateActionSets();
		// legacyWindow.firePerspectiveChanged(this, getPerspective(),
		// CHANGE_RESET);
		// legacyWindow.firePerspectiveChanged(this, getPerspective(),
		// CHANGE_RESET_COMPLETE);
		// }
		// return ret;
		return false;
    }


    /**
     * See IWorkbenchPage@findView.
     */
    public IViewPart findView(String id) {
        IViewReference ref = findViewReference(id);
        if (ref == null) {
			return null;
		}
        return ref.getView(true);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.ui.IWorkbenchPage
     */
    public IViewReference findViewReference(String viewId) {
        return findViewReference(viewId, null);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.ui.IWorkbenchPage
     */
    public IViewReference findViewReference(String viewId, String secondaryId) {
		for (IViewReference reference : viewReferences) {
			if (viewId.equals(reference.getId())) {
				String refSecondaryId = reference.getSecondaryId();
				if (refSecondaryId == null) {
					if (secondaryId == null) {
						return reference;
					}
				} else if (refSecondaryId.equals(secondaryId)) {
					return reference;
				}
			}
		}
		return null;
    }

	public void createViewReferenceForPart(final MPart part, String viewId) {
		IViewDescriptor desc = getWorkbenchWindow().getWorkbench().getViewRegistry().find(viewId);
		final ViewReference ref = new ViewReference(window.getContext(), this, part,
				(ViewDescriptor) desc);
		IEclipseContext partContext = part.getContext();
		if (partContext == null) {
			final IEventBroker broker = (IEventBroker) application.getContext().get(
					IEventBroker.class.getName());
			broker.subscribe(UIEvents.buildTopic(UIEvents.Context.TOPIC, UIEvents.Context.CONTEXT),
					new EventHandler() {
						public void handleEvent(Event event) {
							Object element = event.getProperty(UIEvents.EventTags.ELEMENT);
							if (element == part) {
								if (part.getContext() != null) {
									broker.unsubscribe(this);
									part.getContext().set(ViewReference.class.getName(), ref);
								}
							}
						}
					});
		} else {
			partContext.set(ViewReference.class.getName(), ref);
		}
		viewReferences.add(ref);
	}


    /**
     * Notify property change listeners about a property change.
     * 
     * @param changeId
     *            the change id
     * @param oldValue
     *            old property value
     * @param newValue
     *            new property value
     */
    private void firePropertyChange(String changeId, Object oldValue,
            Object newValue) {
        
        UIListenerLogging.logPagePropertyChanged(this, changeId, oldValue, newValue);
        
        Object[] listeners = propertyChangeListeners.getListeners();
        PropertyChangeEvent event = new PropertyChangeEvent(this, changeId,
                oldValue, newValue);

        for (int i = 0; i < listeners.length; i++) {
            ((IPropertyChangeListener) listeners[i]).propertyChange(event);
        }
    }

    /*
     * Returns the action bars.
     */
    public IActionBars getActionBars() {
        if (actionBars == null) {
			actionBars = new WWinActionBars(legacyWindow);
		}
        return actionBars;
    }

    /**
     * Returns an array of the visible action sets.
     */
    public IActionSetDescriptor[] getActionSets() {
        Collection collection = actionSets.getVisibleItems();
        
        return (IActionSetDescriptor[]) collection.toArray(new IActionSetDescriptor[collection.size()]);
    }

    /**
     * @see IWorkbenchPage
     */
    public IEditorPart getActiveEditor() {
		for (MPart model : activationList) {
			Object object = model.getObject();
			if (object instanceof CompatibilityEditor) {
				CompatibilityEditor editor = (CompatibilityEditor) object;
				// see bug 308492
				if (!editor.isBeingDisposed()) {
					return ((CompatibilityEditor) object).getEditor();
				}
			}
		}
		return null;
    }

    
    public IWorkbenchPart getActivePart() {
		MPart part = partService.getActivePart();
		if (part != null) {
			Object object = part.getObject();
			if (object instanceof CompatibilityPart) {
				return ((CompatibilityPart) object).getPart();
			}
		}
		return null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.IPartService#getActivePartReference()
	 */
	public IWorkbenchPartReference getActivePartReference() {
		IWorkbenchPart part = getActivePart();
		if (part != null) {
			for (IWorkbenchPartReference reference : viewReferences) {
				if (reference.getPart(false) == part) {
					return reference;
				}
			}

			for (IWorkbenchPartReference reference : editorReferences) {
				if (reference.getPart(false) == part) {
					return reference;
				}
			}
		}
		return null;
	}


    /**
     * Returns the client composite.
     */
    public Composite getClientComposite() {
        return composite;
    }


	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.IWorkbenchPage#getDirtyEditors()
	 */
	public IEditorPart[] getDirtyEditors() {
		List<IEditorPart> dirtyEditors = new ArrayList<IEditorPart>();
		for (IEditorReference editorRef : editorReferences) {
			IEditorPart editor = editorRef.getEditor(false);
			if (editor != null && editor.isDirty()) {
				dirtyEditors.add(editor);
			}
		}
		return dirtyEditors.toArray(new IEditorPart[dirtyEditors.size()]);
	}
	
	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.ui.IWorkbenchPage#findEditor(org.eclipse.ui.IEditorInput)
	 */
	public IEditorPart findEditor(IEditorInput input) {
		IEditorReference[] references = findEditors(input, null, MATCH_INPUT);
		return references.length == 0 ? null : references[0].getEditor(true);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.ui.IWorkbenchPage#findEditors(org.eclipse.ui.IEditorInput,
	 * java.lang.String, int)
	 */
	public IEditorReference[] findEditors(IEditorInput input, String editorId, int matchFlags) {
		switch (matchFlags) {
		case MATCH_INPUT:
			List<IEditorReference> editorRefs = new ArrayList<IEditorReference>();
			for (EditorReference editorRef : editorReferences) {
				checkEditor(input, editorRefs, editorRef);
			}
			return editorRefs.toArray(new IEditorReference[editorRefs.size()]);
		case MATCH_ID:
			editorRefs = new ArrayList<IEditorReference>();
			for (IEditorReference editorRef : editorReferences) {
				if (editorId.equals(editorRef.getId())) {
					editorRefs.add(editorRef);
				}
			}
			return editorRefs.toArray(new IEditorReference[editorRefs.size()]);
		default:
			if ((matchFlags & IWorkbenchPage.MATCH_ID) != 0
					&& (matchFlags & IWorkbenchPage.MATCH_INPUT) != 0) {
				editorRefs = new ArrayList<IEditorReference>();
				for (EditorReference editorRef : editorReferences) {
					if (editorRef.getId().equals(editorId)) {
						checkEditor(input, editorRefs, editorRef);
					}
				}
				return editorRefs.toArray(new IEditorReference[editorRefs.size()]);
			}
			return new IEditorReference[0];
		}
	}

	private void checkEditor(IEditorInput input, List<IEditorReference> editorRefs,
			EditorReference editorRef) {
		IEditorMatchingStrategy strategy = editorRef.getDescriptor().getEditorMatchingStrategy();
		if (strategy != null && strategy.matches(editorRef, input)) {
			editorRefs.add(editorRef);
			return;
		}

		IEditorPart editor = editorRef.getEditor(false);
		if (editor == null) {
			try {
				if (input.equals(editorRef.getEditorInput())) {
					editorRefs.add(editorRef);
				}
			} catch (PartInitException e) {
				WorkbenchPlugin.log(e);
			}
		} else if (editor.getEditorInput().equals(input)) {
			editorRefs.add(editorRef);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.IWorkbenchPage#getEditors()
	 */
	public IEditorPart[] getEditors() {
		int length = editorReferences.size();
		IEditorPart[] editors = new IEditorPart[length];
		for (int i = 0; i < length; i++) {
			editors[i] = editorReferences.get(i).getEditor(true);
		}
		return editors;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.IWorkbenchPage#getEditorReferences()
	 */
	public IEditorReference[] getEditorReferences() {
		List<IEditorReference> sortedReferences = new ArrayList<IEditorReference>();
		for (MPart part : activationList) {
			for (EditorReference ref : editorReferences) {
				if (ref.getModel() == part) {
					sortedReferences.add(ref);
					break;
				}
			}
		}

		for (EditorReference ref : editorReferences) {
			if (!sortedReferences.contains(ref)) {
				sortedReferences.add(ref);
			}
		}

		return sortedReferences.toArray(new IEditorReference[sortedReferences.size()]);
	}

	public IWorkbenchPartReference[] getSortedParts() {
		List<IWorkbenchPartReference> sortedReferences = new ArrayList<IWorkbenchPartReference>();

		activationLoop: for (MPart part : activationList) {
			for (ViewReference ref : viewReferences) {
				if (ref.getModel() == part) {
					sortedReferences.add(ref);
					continue activationLoop;
				}
			}

			for (EditorReference ref : editorReferences) {
				if (ref.getModel() == part) {
					sortedReferences.add(ref);
					break;
				}
			}
		}

		for (ViewReference ref : viewReferences) {
			if (!sortedReferences.contains(ref)) {
				sortedReferences.add(ref);
			}
		}

		for (EditorReference ref : editorReferences) {
			if (!sortedReferences.contains(ref)) {
				sortedReferences.add(ref);
			}
		}

		return sortedReferences.toArray(new IWorkbenchPartReference[sortedReferences.size()]);
	}

    /**
     * @see IWorkbenchPage
     */
    public IAdaptable getInput() {
        return input;
    }

    /**
     * Returns the page label. This is a combination of the page input and
     * active perspective.
     */
    public String getLabel() {
        String label = WorkbenchMessages.WorkbenchPage_UnknownLabel;
        IWorkbenchAdapter adapter = (IWorkbenchAdapter) Util.getAdapter(input, 
                IWorkbenchAdapter.class);
        if (adapter != null) {
			label = adapter.getLabel(input);
		}
		// Perspective persp = getActivePerspective();
		// if (persp != null) {
		// label = NLS.bind(WorkbenchMessages.WorkbenchPage_PerspectiveFormat,
		// label, persp.getDesc().getLabel());
		// } else if (deferredActivePersp != null) {
		// label =
		// NLS.bind(WorkbenchMessages.WorkbenchPage_PerspectiveFormat,label,
		// deferredActivePersp.getLabel());
		// }
        return label;
    }

    /**
     * Returns the perspective.
     */
    public IPerspectiveDescriptor getPerspective() {
		MPerspectiveStack ps = getPerspectiveStack();
		MPerspective curPersp = ps.getSelectedElement();
		if (curPersp == null)
			return null;

		IPerspectiveDescriptor desc = PlatformUI.getWorkbench().getPerspectiveRegistry()
				.findPerspectiveWithId(curPersp.getElementId());
		return desc;
	}

    /*
     * (non-Javadoc) Method declared on ISelectionService
     */
    public ISelection getSelection() {
		return getWorkbenchWindow().getSelectionService().getSelection();
    }

    /*
     * (non-Javadoc) Method declared on ISelectionService
     */
    public ISelection getSelection(String partId) {
		return getWorkbenchWindow().getSelectionService().getSelection(partId);
    }

    /**
     * Returns the ids of the parts to list in the Show In... prompter. This is
     * a List of Strings.
     */
    public ArrayList getShowInPartIds() {
		MPerspective perspective = getPerspectiveStack().getSelectedElement();
		return new ArrayList(ModeledPageLayout.getIds(perspective,
				ModeledPageLayout.SHOW_IN_PART_TAG));
    }

    /**
     * The user successfully performed a Show In... action on the specified
     * part. Update the list of Show In items accordingly.
     */
    public void performedShowIn(String partId) {
		// TODO compat: show in
    }

    /**
     * Sorts the given collection of show in target part ids in MRU order.
     */
    public void sortShowInPartIds(ArrayList partIds) {
		// TODO compat: can't sort what we don't have
    }


    /**
     * See IWorkbenchPage.
     */
    public IViewReference[] getViewReferences() {
		return viewReferences.toArray(new IViewReference[viewReferences.size()]);
	}

    /**
     * See IWorkbenchPage.
     */
    public IViewPart[] getViews() {
		int length = viewReferences.size();
		IViewPart[] views = new IViewPart[length];
		for (int i = 0; i < length; i++) {
			views[i] = viewReferences.get(i).getView(true);
		}
		return views;
	}
	


    /**
	 * See IWorkbenchPage.
	 */
    public IWorkbenchWindow getWorkbenchWindow() {
		return legacyWindow;
    }

    /**
     * Implements IWorkbenchPage
     * 
     * @see org.eclipse.ui.IWorkbenchPage#getWorkingSet()
     * @since 2.0
     * @deprecated individual views should store a working set if needed
     */
    public IWorkingSet getWorkingSet() {
        return workingSet;
    }

    /**
     * @see IWorkbenchPage
     */
    public void hideActionSet(String actionSetID) {
		MPerspective persp = getPerspectiveStack().getSelectedElement();
		if (persp == null) {
			return;
		}
		EContextService contextService = window.getContext().get(EContextService.class);
		String tag = ModeledPageLayout.ACTION_SET_TAG + actionSetID;
		if (persp.getTags().contains(tag)) {
			persp.getTags().remove(tag);
			contextService.deactivateContext(actionSetID);
		}
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.ui.IWorkbenchPage#hideView(org.eclipse.ui.IViewReference)
     */
	public void hideView(IViewReference view) {
        
    	if (view != null) {
			hidePart(view.getId());
		}

		// Notify interested listeners after the hide
		legacyWindow.firePerspectiveChanged(this, getPerspective(), CHANGE_VIEW_HIDE);
	}

	/**
	 * See IPerspective
	 */
	public void hideView(IViewPart view) {
		if (view != null) {
			hidePart(view.getSite().getId());
		}
	}

    /**
     * Initialize the page.
     * 
     * @param w
     *            the parent window
     * @param layoutID
     *            may be <code>null</code> if restoring from file
     * @param input
     *            the page input
     * @param openExtras
     *            whether to process the perspective extras preference
     */
    private void init(WorkbenchWindow w, String layoutID, IAdaptable input, boolean openExtras)
            throws WorkbenchException {
        // Save args.
		this.legacyWindow = w;
        this.input = input;
        actionSets = new ActionSetManager(w);

	}

	@PostConstruct
	public void setup() {
		partService.addPartListener(e4PartListener);

		Collection<MPart> parts = partService.getParts();
		for (MPart part : parts) {
			if (part.isToBeRendered()) {
				String uri = part.getContributionURI();
				if (uri.equals(CompatibilityPart.COMPATIBILITY_VIEW_URI)) {
					createViewReferenceForPart(part, part.getElementId());
				} else if (uri.equals(CompatibilityPart.COMPATIBILITY_EDITOR_URI)) {
					// TODO compat: we need that editor input back, or we have
					// squat
					createEditorReferenceForPart(part, null, part.getElementId());
				}
			}
		}

		broker.subscribe(UIEvents.buildTopic(UIEvents.ElementContainer.TOPIC,
				UIEvents.ElementContainer.SELECTEDELEMENT), selectionHandler);
		if (getPerspectiveStack() != null) {
			MPerspective persp = getPerspectiveStack().getSelectedElement();
			List<String> newIds = ModeledPageLayout.getIds(persp, ModeledPageLayout.ACTION_SET_TAG);
			EContextService contextService = window.getContext().get(EContextService.class);
			for (String id : newIds) {
				contextService.activateContext(id);
			}
		}
    }

	private EventHandler selectionHandler = new EventHandler() {
		public void handleEvent(Event event) {
			MUIElement changedElement = (MUIElement) event.getProperty(UIEvents.EventTags.ELEMENT);

			if (changedElement != getPerspectiveStack()) {
				return;
			}

			EContextService contextService = window.getContext().get(EContextService.class);
			MPerspective oldPersp = (MPerspective) event.getProperty(UIEvents.EventTags.OLD_VALUE);
			List<String> oldIds = ModeledPageLayout.getIds(oldPersp,
					ModeledPageLayout.ACTION_SET_TAG);
			MPerspective newPersp = (MPerspective) event.getProperty(UIEvents.EventTags.NEW_VALUE);
			List<String> newIds = ModeledPageLayout.getIds(newPersp,
					ModeledPageLayout.ACTION_SET_TAG);

			oldIds.removeAll(newIds);
			for (String id : oldIds) {
				contextService.deactivateContext(id);
			}
			for (String id : newIds) {
				contextService.activateContext(id);
			}

			List<MPart> hiddenParts = new ArrayList<MPart>();
			List<MPart> visibleParts = new ArrayList<MPart>();

			List<MPartStack> oldStacks = modelService.findElements(oldPersp, null,
					MPartStack.class, null);
			List<MPartStack> newStacks = modelService.findElements(newPersp, null,
					MPartStack.class, null);

			for (MPartStack oldStack : oldStacks) {
				MStackElement element = oldStack.getSelectedElement();
				if (element instanceof MPlaceholder) {
					hiddenParts.add((MPart) ((MPlaceholder) element).getRef());
				} else if (element instanceof MPart) {
					hiddenParts.add((MPart) element);
				}
			}

			for (MPartStack newStack : newStacks) {
				MStackElement element = newStack.getSelectedElement();
				if (element instanceof MPlaceholder) {
					visibleParts.add((MPart) ((MPlaceholder) element).getRef());
				} else if (element instanceof MPart) {
					visibleParts.add((MPart) element);
				}
			}

			List<MPart> ignoredParts = new ArrayList<MPart>();
			for (MPart hiddenPart : hiddenParts) {
				if (visibleParts.contains(hiddenPart)) {
					ignoredParts.add(hiddenPart);
				}
			}

			hiddenParts.removeAll(ignoredParts);
			visibleParts.removeAll(ignoredParts);

			for (MPart hiddenPart : hiddenParts) {
				firePartHidden(hiddenPart);
			}

			for (MPart visiblePart : visibleParts) {
				firePartVisible(visiblePart);
			}

			legacyWindow.firePerspectiveActivated(WorkbenchPage.this, perspective);
		}
	};

	/**
     * See IWorkbenchPage.
     */
    public boolean isPartVisible(IWorkbenchPart part) {
		MPart mpart = findPart(part);
		return mpart == null ? false : partService.isPartVisible(mpart);
    }
    
    /**
     * See IWorkbenchPage.
     */
    public boolean isEditorAreaVisible() {
		// FIXME compat isEditorAreaVisible
		E4Util.unsupported("isEditorAreaVisible"); //$NON-NLS-1$
		return true;
    }

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.IWorkbenchPage#isPageZoomed()
	 */
    public boolean isPageZoomed() {
		// FIXME compat: the page is not zoomed :-)
		return false;
    }
    

	// /**
	// * This method is called when the page is activated.
	// */
	// protected void onActivate() {
	// composite.setVisible(true);
	// Perspective persp = getActivePerspective();
	//
	// if (persp != null) {
	// persp.onActivate();
	// updateVisibility(null, persp);
	// }
	// }
	//
	// /**
	// * This method is called when the page is deactivated.
	// */
	// protected void onDeactivate() {
	// makeActiveEditor(null);
	// makeActive(null);
	// if (getActivePerspective() != null) {
	// getActivePerspective().onDeactivate();
	// }
	// composite.setVisible(false);
	// }

    /**
     * See IWorkbenchPage.
     */
    public void reuseEditor(IReusableEditor editor, IEditorInput input) {
        
        // Rather than calling editor.setInput on the editor directly, we do it through the part reference.
        // This case lets us detect badly behaved editors that are not firing a PROP_INPUT event in response
        // to the input change... but if all editors obeyed their API contract, the "else" branch would be
        // sufficient.

		// TODO compat: should we be talking to the editor reference here
		editor.setInput(input);
        navigationHistory.markEditor(editor);
    }

    /**
     * See IWorkbenchPage.
     */
    public IEditorPart openEditor(IEditorInput input, String editorID)
            throws PartInitException {
        return openEditor(input, editorID, true, MATCH_INPUT);
    }

    /**
     * See IWorkbenchPage.
     */
    public IEditorPart openEditor(IEditorInput input, String editorID,
			boolean activate) throws PartInitException {
		return openEditor(input, editorID, activate, MATCH_INPUT);
    }
	
    /**
     * See IWorkbenchPage.
     */
    public IEditorPart openEditor(final IEditorInput input,
            final String editorID, final boolean activate, final int matchFlags)
            throws PartInitException {
    	return openEditor(input, editorID, activate, matchFlags, null);
    }
	
    /**
     * This is not public API but for use internally.  editorState can be <code>null</code>.
     */
    public IEditorPart openEditor(final IEditorInput input,
            final String editorID, final boolean activate, final int matchFlags,
            final IMemento editorState)
            throws PartInitException {
        if (input == null || editorID == null) {
            throw new IllegalArgumentException();
        }

        final IEditorPart result[] = new IEditorPart[1];
        final PartInitException ex[] = new PartInitException[1];
		BusyIndicator.showWhile(legacyWindow.getWorkbench().getDisplay(),
                new Runnable() {
                    public void run() {
                        try {
                            result[0] = busyOpenEditor(input, editorID,
                                    activate, matchFlags, editorState);
                        } catch (PartInitException e) {
                            ex[0] = e;
                        }
                    }
                });
        if (ex[0] != null) {
			throw ex[0];
		}
        return result[0];
    }


    
    /**
     * @see #openEditor(IEditorInput, String, boolean, int)
	 */
	private IEditorPart busyOpenEditor(IEditorInput input, String editorId,
            boolean activate, int matchFlags, IMemento editorState) throws PartInitException {

		if (input == null || editorId == null) {
			throw new IllegalArgumentException();
		}

		// Special handling for external editors (they have no tabs...)
		if ("org.eclipse.ui.systemExternalEditor".equals(editorId) //$NON-NLS-1$
				|| "org.eclipse.ui.browser.editorSupport".equals(editorId)) { //$NON-NLS-1$
			if (input instanceof IPathEditorInput) {
				IPathEditorInput fileInput = (IPathEditorInput) input;
				String fullPath = fileInput.getPath().toOSString();
				Program.launch(fullPath);
				return null;
			}
		}

		IEditorReference[] editorReferences = findEditors(input, editorId, matchFlags);
		if (editorReferences.length != 0) {
			IEditorPart editor = editorReferences[0].getEditor(true);
			if (editor instanceof IShowEditorInput) {
				((IShowEditorInput) editor).showEditorInput(input);
			}

			if (activate) {
				activate(editor);
			}
			return editor;
		}

		MPart editor = partService.createPart("org.eclipse.e4.ui.compatibility.editor"); //$NON-NLS-1$
		createEditorReferenceForPart(editor, input, editorId);
		partService.showPart(editor, PartState.VISIBLE);

		CompatibilityEditor compatibilityEditor = (CompatibilityEditor) editor.getObject();

		if (activate) {
			partService.activate(editor);
			compatibilityEditor.delegateSetFocus();
		}


		return compatibilityEditor.getEditor();
    }


    /**
     * See IWorkbenchPage.
     */
    public boolean isEditorPinned(IEditorPart editor) {
    	WorkbenchPartReference ref = (WorkbenchPartReference)getReference(editor); 
        return ref != null && ref.isPinned();
    }
    


    /**
     * Removes an IPartListener from the part service.
     */
    public void removePartListener(IPartListener l) {
		partListenerList.remove(l);
    }

    /**
     * Removes an IPartListener from the part service.
     */
    public void removePartListener(IPartListener2 l) {
		partListener2List.remove(l);
    }

    /**
     * Implements IWorkbenchPage
     * 
     * @see org.eclipse.ui.IWorkbenchPage#removePropertyChangeListener(IPropertyChangeListener)
     * @since 2.0
     * @deprecated individual views should store a working set if needed and
     *             register a property change listener directly with the
     *             working set manager to receive notification when the view
     *             working set is removed.
     */
    public void removePropertyChangeListener(IPropertyChangeListener listener) {
        propertyChangeListeners.remove(listener);
    }

    /*
     * (non-Javadoc) Method declared on ISelectionListener.
     */
    public void removeSelectionListener(ISelectionListener listener) {
		getWorkbenchWindow().getSelectionService().removeSelectionListener(listener);
    }

    /*
     * (non-Javadoc) Method declared on ISelectionListener.
     */
    public void removeSelectionListener(String partId,
            ISelectionListener listener) {
		getWorkbenchWindow().getSelectionService().removeSelectionListener(partId, listener);
    }

    /*
     * (non-Javadoc) Method declared on ISelectionListener.
     */
    public void removePostSelectionListener(ISelectionListener listener) {
		getWorkbenchWindow().getSelectionService().removePostSelectionListener(listener);
    }

    /*
     * (non-Javadoc) Method declared on ISelectionListener.
     */
    public void removePostSelectionListener(String partId,
            ISelectionListener listener) {
		getWorkbenchWindow().getSelectionService().removePostSelectionListener(partId, listener);
    }



    /**
     * Resets the layout for the perspective. The active part in the old layout
     * is activated in the new layout for consistent user context.
     */
    public void resetPerspective() {
		MPerspectiveStack perspStack = getPerspectiveStack();
		MPerspective persp = perspStack.getSelectedElement();
		if (persp == null)
			return;

		// HACK!! the 'perspective' field doesn't match reality...
		IPerspectiveDescriptor desc = PlatformUI.getWorkbench().getPerspectiveRegistry()
				.findPerspectiveWithId(persp.getElementId());
		if (desc == null)
			return;

		// For now just close and re-open it
		closePerspective(desc, true, false);
		if (persp.getParent() == null) {
			setPerspective(desc);
		}
	}



    /**
     * See IWorkbenchPage
     */
    public boolean saveAllEditors(boolean confirm) {
        return saveAllEditors(confirm, false);
    }

	private boolean saveAllEditors(boolean confirm, boolean closing) {
		if (closing) {
			for (IEditorReference editorReference : getEditorReferences()) {
				// save all restored editors
				IEditorPart editor = editorReference.getEditor(false);
				if (editor != null) {
					if (!saveSaveable(editor, confirm, closing)) {
						return false;
					}
				}
			}

			for (IViewReference viewReference : viewReferences) {
				// save all restored views
				IWorkbenchPart part = viewReference.getPart(false);
				if (part instanceof ISaveablePart) {
					if (!saveSaveable((ISaveablePart) part, confirm, closing)) {
						return false;
					}
				}
			}
		}

		return partService.saveAll(confirm);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.IWorkbenchPage#saveEditor(org.eclipse.ui.IEditorPart,
	 * boolean)
	 */
	public boolean saveSaveable(ISaveablePart saveable, boolean confirm, boolean closing) {
		MPart part = findPart((IWorkbenchPart) saveable);
		if (part != null) {
			if (saveable.isDirty()) {
				if (closing) {
					if (saveable.isSaveOnCloseNeeded()) {
						return partService.savePart(part, confirm);
					}
				} else {
					return partService.savePart(part, confirm);
				}
			}
			return true;
		}
		return false;
	}


    /**
     * Saves an editors in the workbench. If <code>confirm</code> is <code>true</code>
     * the user is prompted to confirm the command.
     * 
     * @param confirm
     *            if user confirmation should be sought
     * @return <code>true</code> if the command succeeded, or <code>false</code>
     *         if the user cancels the command
     */
    public boolean saveEditor(IEditorPart editor, boolean confirm) {
		return saveSaveable(editor, confirm, false);
    }

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.IWorkbenchPage#savePerspective()
	 */
	public void savePerspective() {
		// FIXME compat savePerspective
		E4Util.unsupported("savePerspective"); //$NON-NLS-1$

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.IWorkbenchPage#savePerspectiveAs(org.eclipse.ui.
	 * IPerspectiveDescriptor)
	 */
	public void savePerspectiveAs(IPerspectiveDescriptor perspective) {
		// FIXME compat savePerspectiveAs
		E4Util.unsupported("savePerspectiveAs"); //$NON-NLS-1$

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.IWorkbenchPage#setEditorAreaVisible(boolean)
	 */
	public void setEditorAreaVisible(boolean showEditorArea) {
		// FIXME compat setEditorAreaVisible
		E4Util.unsupported("setEditorAreaVisible"); //$NON-NLS-1$

	}

    




	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.IWorkbenchPage#setPerspective(org.eclipse.ui.
	 * IPerspectiveDescriptor)
	 */
	public void setPerspective(IPerspectiveDescriptor perspective) {
		if (perspective == null) {
			return;
		}

		IPerspectiveDescriptor lastPerspective = this.perspective;
		this.perspective = perspective;

		if (sortedPerspectives.contains(perspective)) {
			sortedPerspectives.remove(perspective);
		}
		sortedPerspectives.add(perspective);

		MPerspectiveStack perspectives = getPerspectiveStack();
		for (MPerspective mperspective : perspectives.getChildren()) {
			if (mperspective.getElementId().equals(perspective.getId())) {
				// // instantiate the perspective
				// IPerspectiveFactory factory = ((PerspectiveDescriptor)
				// perspective).createFactory();
				// // use a new perspective since we're only interested in
				// // shortcuts here, see bug 305918
				// modelLayout = new ModeledPageLayout(window, modelService,
				// partService,
				// AdvancedFactoryImpl.eINSTANCE.createPerspective(),
				// perspective, this, false);
				// factory.createInitialLayout(modelLayout);

				if (lastPerspective != null) {
					legacyWindow.firePerspectiveDeactivated(this, lastPerspective);
				}

				// this perspective already exists, switch to this one
				perspectives.setSelectedElement(mperspective);
				window.getContext().set(IContextConstants.ACTIVE_CHILD, mperspective.getContext());
				legacyWindow.firePerspectiveDeactivated(this, perspective);
				return;
			}
		}

		// couldn't find the perspective, create a new one
		MPerspective modelPerspective = AdvancedFactoryImpl.eINSTANCE.createPerspective();
		// tag it with the same id
		modelPerspective.setElementId(perspective.getId());
		modelPerspective.setLabel(perspective.getLabel());

		// instantiate the perspective
		IPerspectiveFactory factory = ((PerspectiveDescriptor) perspective).createFactory();
		ModeledPageLayout modelLayout = new ModeledPageLayout(window, modelService, partService,
				modelPerspective,
				perspective, this, true);
		factory.createInitialLayout(modelLayout);
		PerspectiveTagger.tagPerspective(modelPerspective, modelService);
		PerspectiveExtensionReader reader = new PerspectiveExtensionReader();
		reader.extendLayout(getExtensionTracker(), perspective.getId(), modelLayout);

		if (lastPerspective != null) {
			legacyWindow.firePerspectiveDeactivated(this, lastPerspective);
		}

		// add it to the stack
		perspectives.getChildren().add(modelPerspective);
		// activate it
		perspectives.setSelectedElement(modelPerspective);
		window.getContext().set(IContextConstants.ACTIVE_CHILD, modelPerspective.getContext());
		legacyWindow.firePerspectiveDeactivated(this, perspective);

		// FIXME: we need to fire events
	}


	/**
	 * Retrieves the perspective stack of the window that's containing this
	 * workbench page.
	 * 
	 * @return the stack of perspectives of this page's containing window
	 */
	private MPerspectiveStack getPerspectiveStack() {
		for (MWindowElement child : window.getChildren()) {
			if (child instanceof MPerspectiveStack) {
				return (MPerspectiveStack) child;
			}
		}

		MPerspectiveStack perspectiveStack = AdvancedFactoryImpl.eINSTANCE.createPerspectiveStack();
		window.getChildren().add(perspectiveStack);
		window.setSelectedElement(perspectiveStack);
		return perspectiveStack;
	}
    


    /**
     * Sets the active working set for the workbench page. Notifies property
     * change listener about the change.
     * 
     * @param newWorkingSet
     *            the active working set for the page. May be null.
     * @since 2.0
     * @deprecated individual views should store a working set if needed
     */
    public void setWorkingSet(IWorkingSet newWorkingSet) {
        IWorkingSet oldWorkingSet = workingSet;

        workingSet = newWorkingSet;
        if (oldWorkingSet != newWorkingSet) {
            firePropertyChange(CHANGE_WORKING_SET_REPLACE, oldWorkingSet,
                    newWorkingSet);
        }
        if (newWorkingSet != null) {
            WorkbenchPlugin.getDefault().getWorkingSetManager()
                    .addPropertyChangeListener(workingSetPropertyChangeListener);
        } else {
            WorkbenchPlugin.getDefault().getWorkingSetManager()
                    .removePropertyChangeListener(workingSetPropertyChangeListener);
        }
    }

    /**
     * @see IWorkbenchPage
     */
    public void showActionSet(String actionSetID) {
		MPerspective persp = getPerspectiveStack().getSelectedElement();
		if (persp == null) {
			return;
		}
		EContextService contextService = window.getContext().get(EContextService.class);
		String tag = ModeledPageLayout.ACTION_SET_TAG + actionSetID;
		if (!persp.getTags().contains(tag)) {
			persp.getTags().add(tag);
			contextService.activateContext(actionSetID);
		}
    }

    /**
     * See IWorkbenchPage.
     */
    public IViewPart showView(String viewID) throws PartInitException {
        return showView(viewID, null, VIEW_ACTIVATE);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.ui.IWorkbenchPage#showView(java.lang.String,
     *      java.lang.String, int)
     */
    public IViewPart showView(final String viewID, final String secondaryID,
            final int mode) throws PartInitException {

        if (secondaryID != null) {
            if (secondaryID.length() == 0
 || secondaryID.indexOf(":") != -1) { //$NON-NLS-1$
				throw new IllegalArgumentException(WorkbenchMessages.WorkbenchPage_IllegalSecondaryId);
			} 
        }
        if (!certifyMode(mode)) {
			throw new IllegalArgumentException(WorkbenchMessages.WorkbenchPage_IllegalViewMode);
		}

        // Run op in busy cursor.
        final Object[] result = new Object[1];
        BusyIndicator.showWhile(null, new Runnable() {
            public void run() {
                try {
                    result[0] = busyShowView(viewID, secondaryID, mode);
                } catch (PartInitException e) {
                    result[0] = e;
                }
            }
        });
        if (result[0] instanceof IViewPart) {
			return (IViewPart) result[0];
		} else if (result[0] instanceof PartInitException) {
			throw (PartInitException) result[0];
		} else {
			throw new PartInitException(WorkbenchMessages.WorkbenchPage_AbnormalWorkbenchCondition);
		} 
    }

    /**
     * @param mode the mode to test
     * @return whether the mode is recognized
     * @since 3.0
     */
    private boolean certifyMode(int mode) {
        switch (mode) {
        case VIEW_ACTIVATE:
        case VIEW_VISIBLE:
        case VIEW_CREATE:
            return true;
        default:
            return false;
        }
    }

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.IWorkbenchPage#setPartState(org.eclipse.ui.
	 * IWorkbenchPartReference, int)
	 */
    public void setPartState(IWorkbenchPartReference ref, int state) {
		// FIXME compat setPartState
		E4Util.unsupported("setPartState"); //$NON-NLS-1$
    }


    
    /*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.IWorkbenchPage#getPartState(org.eclipse.ui.
	 * IWorkbenchPartReference)
	 */
    public int getPartState(IWorkbenchPartReference ref) {
		// FIXME compat getPartState
		return STATE_RESTORED;
	}

    /**
     * updateActionBars method comment.
     */
    public void updateActionBars() {
		legacyWindow.updateActionBars();
    }


    
    /* (non-Javadoc)
     * @see org.eclipse.ui.IWorkbenchPage#zoomOut()
     */
    public void zoomOut() {
		// TODO compat: what does the zoom do?
    }

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.IWorkbenchPage#toggleZoom(org.eclipse.ui.
	 * IWorkbenchPartReference)
	 */
	public void toggleZoom(IWorkbenchPartReference ref) {
		MPerspective curPersp = modelService.getActivePerspective(window);
		// HACK!! Constant is defined in MinMaxAddon
		String EAMaximizedHack = "EAMaximized"; //$NON-NLS-1$
		if (curPersp.getTags().contains(EAMaximizedHack)) {
			curPersp.getTags().remove(EAMaximizedHack);
		} else {
			curPersp.getTags().add(EAMaximizedHack);
		}
	}


	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.IWorkbenchPage#getOpenPerspectives()
	 */
	public IPerspectiveDescriptor[] getOpenPerspectives() {
		MPerspectiveStack perspectiveStack = modelService.findElements(window, null,
				MPerspectiveStack.class, null).get(0);
		IPerspectiveRegistry registry = PlatformUI.getWorkbench().getPerspectiveRegistry();

		IPerspectiveDescriptor[] descs = new IPerspectiveDescriptor[perspectiveStack.getChildren()
				.size()];
		int count = 0;
		for (MPerspective persp : perspectiveStack.getChildren()) {
			IPerspectiveDescriptor desc = registry.findPerspectiveWithId(persp.getElementId());
			descs[count++] = desc;
		}
		return descs;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.IWorkbenchPage#getSortedPerspectives()
	 */
	public IPerspectiveDescriptor[] getSortedPerspectives() {
		return sortedPerspectives.toArray(new IPerspectiveDescriptor[sortedPerspectives.size()]);
	}



    /**
     * Returns the reference to the given part, or <code>null</code> if it has no reference 
     * (i.e. it is not a top-level part in this workbench page).
     * 
     * @param part the part
     * @return the part's reference or <code>null</code> if the given part does not belong 
     * to this workbench page
     */
	public IWorkbenchPartReference getReference(IWorkbenchPart part) {
		for (IEditorReference editorRef : editorReferences) {
			if (editorRef.getPart(false) == part) {
				return editorRef;
			}
		}

		for (IViewReference viewRef : viewReferences) {
			if (viewRef.getPart(false) == part) {
				return viewRef;
			}
		}

		return null;
	}

    

    

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.ui.IWorkbenchPage#getViewStack(org.eclipse.ui.IViewPart)
     */
	public IViewPart[] getViewStack(IViewPart part) {
		MPart mpart = partService.findPart(part.getSite().getId());
		if (mpart != null) {
			MElementContainer<?> parent = mpart.getParent();
			if (parent == null) {
				// this is a shared part, check for placeholders
				MPlaceholder placeholder = mpart.getCurSharedRef();
				if (placeholder != null) {
					parent = placeholder.getParent();
				}
			}

			if (parent instanceof MPartStack) {
				List<CompatibilityView> stack = new ArrayList<CompatibilityView>();

				for (Object child : parent.getChildren()) {
					MPart siblingPart = child instanceof MPart ? (MPart) child
							: (MPart) ((MPlaceholder) child).getRef();
					Object siblingObject = siblingPart.getObject();
					if (siblingObject instanceof CompatibilityView) {
						stack.add((CompatibilityView) siblingObject);
					}
				}

				// sort the list by activation order (most recently activated
				// first)
				Collections.sort(stack, new Comparator<CompatibilityView>() {
					public int compare(CompatibilityView o1, CompatibilityView o2) {
						int pos1 = (-1) * activationList.indexOf(o1.getModel());
						int pos2 = (-1) * activationList.indexOf(o2.getModel());
						return pos1 - pos2;
					}
				});

				IViewPart[] result = new IViewPart[stack.size()];
				for (int i = 0; i < result.length; i++) {
					result[i] = stack.get(i).getView();
				}
				return result;
			}

			// not in a stack, standalone
			return new IViewPart[] { part };
		}
		return null;
	}


	/* (non-Javadoc)
	 * @see org.eclipse.ui.IWorkbenchPage#getExtensionTracker()
	 */
	public IExtensionTracker getExtensionTracker() {
		if (tracker == null) {
			tracker = new UIExtensionTracker(getWorkbenchWindow().getWorkbench().getDisplay());
		}
		return tracker;		
	}

	private final static String[] EMPTY_STRING_ARRAY = new String[0];

	private String[] getArrayForTag(String tagPrefix) {
		MPerspective perspective = getPerspectiveStack().getSelectedElement();
		if (perspective == null) {
			return EMPTY_STRING_ARRAY;
		}
		List<String> id = ModeledPageLayout.getIds(perspective, tagPrefix);
		if (id.size() == 0) {
			return EMPTY_STRING_ARRAY;
		}
		return id.toArray(new String[id.size()]);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.IWorkbenchPage#getNewWizardShortcuts()
	 */
	public String[] getNewWizardShortcuts() {
		return getArrayForTag(ModeledPageLayout.NEW_WIZARD_TAG);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.IWorkbenchPage#getPerspectiveShortcuts()
	 */
	public String[] getPerspectiveShortcuts() {
		return getArrayForTag(ModeledPageLayout.PERSP_SHORTCUT_TAG);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.IWorkbenchPage#getShowViewShortcuts()
	 */
	public String[] getShowViewShortcuts() {
		return getArrayForTag(ModeledPageLayout.SHOW_VIEW_TAG);
	}
    
 
    
    public boolean isPartVisible(IWorkbenchPartReference reference) {        
        IWorkbenchPart part = reference.getPart(false);
        // Can't be visible if it isn't created yet
        if (part == null) {
            return false;
        }
        
        return isPartVisible(part);
    }

	public IWorkingSet[] getWorkingSets() {
		return workingSets;
	}

	public void setWorkingSets(IWorkingSet[] newWorkingSets) {
		if (newWorkingSets != null) {
			WorkbenchPlugin
					.getDefault()
					.getWorkingSetManager()
					.addPropertyChangeListener(workingSetPropertyChangeListener);
		} else {
			WorkbenchPlugin.getDefault().getWorkingSetManager()
					.removePropertyChangeListener(
							workingSetPropertyChangeListener);
		}

		if (newWorkingSets == null) {
			newWorkingSets = new IWorkingSet[0];
		}

		IWorkingSet[] oldWorkingSets = workingSets;
		
		// filter out any duplicates if necessary
		if (newWorkingSets.length > 1) {	
			Set setOfSets = new HashSet();
			for (int i = 0; i < newWorkingSets.length; i++) {
				if (newWorkingSets[i] == null) {
					throw new IllegalArgumentException();
				}
				setOfSets.add(newWorkingSets[i]);
			}
			newWorkingSets = (IWorkingSet[]) setOfSets
					.toArray(new IWorkingSet[setOfSets.size()]);
		}

		workingSets = newWorkingSets;
		if (!Arrays.equals(oldWorkingSets, newWorkingSets)) {
			firePropertyChange(CHANGE_WORKING_SETS_REPLACE, oldWorkingSets,
					newWorkingSets);
			if (aggregateWorkingSet != null) {
				aggregateWorkingSet.setComponents(workingSets);
			}
		}
	}
	
	public IWorkingSet getAggregateWorkingSet() {
		if (aggregateWorkingSet == null) {
			IWorkingSetManager workingSetManager = PlatformUI.getWorkbench()
					.getWorkingSetManager();
			aggregateWorkingSet = (AggregateWorkingSet) workingSetManager.getWorkingSet(
							getAggregateWorkingSetId());
			if (aggregateWorkingSet == null) {
				aggregateWorkingSet = (AggregateWorkingSet) workingSetManager
						.createAggregateWorkingSet(
								getAggregateWorkingSetId(),
								WorkbenchMessages.WorkbenchPage_workingSet_default_label,
								getWorkingSets());
				workingSetManager.addWorkingSet(aggregateWorkingSet);
			}
		}
		return aggregateWorkingSet;
	}

	private String getAggregateWorkingSetId() {	
		if (aggregateWorkingSetId == null) {
			aggregateWorkingSetId = "Aggregate for window " + System.currentTimeMillis(); //$NON-NLS-1$
		}
		return aggregateWorkingSetId;
	}
	
	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.ui.IWorkbenchPage#showEditor(org.eclipse.ui.IEditorReference)
	 */
	public void showEditor(IEditorReference ref) {
		// FIXME compat showEditor
		E4Util.unsupported("showEditor"); //$NON-NLS-1$

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.ui.IWorkbenchPage#hideEditor(org.eclipse.ui.IEditorReference)
	 */
	public void hideEditor(IEditorReference ref) {
		// FIXME compat hideEditor
		E4Util.unsupported("hideEditor"); //$NON-NLS-1$

	}
	
	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.ui.IWorkbenchPage#openEditors(org.eclipse.ui.IEditorInput[],
	 * java.lang.String[], int)
	 */
	public IEditorReference[] openEditors(IEditorInput[] inputs, String[] editorIDs, int matchFlags)
			throws MultiPartInitException {
		Assert.isTrue(inputs.length == editorIDs.length);

		PartInitException[] exceptions = new PartInitException[inputs.length];
		IEditorReference[] references = new IEditorReference[inputs.length];
		boolean hasFailures = false;

		for (int i = 0; i < inputs.length; i++) {
			try {
				IEditorPart editor = openEditor(inputs[i], editorIDs[i], i == 0, matchFlags);
				references[i] = (IEditorReference) getReference(editor);
			} catch (PartInitException e) {
				if (!hasFailures) {
					hasFailures = true;
					exceptions[i] = e;
				}
			}
		}

		if (hasFailures) {
			throw new MultiPartInitException(references, exceptions);
		}

		return references;
	}

	private void firePartActivated(MPart part) {
		Object client = part.getObject();
		if (client instanceof CompatibilityPart) {
			IWorkbenchPart workbenchPart = ((CompatibilityPart) client).getPart();
			IWorkbenchPartReference partReference = getReference(workbenchPart);
			if (partReference == null) {
				WorkbenchPlugin.log("Reference is null in firePartActivated"); //$NON-NLS-1$
				return;
			}

			for (Object listener : partListenerList.getListeners()) {
				((IPartListener) listener).partActivated(workbenchPart);
			}

			for (Object listener : partListener2List.getListeners()) {
				((IPartListener2) listener).partActivated(partReference);
			}
		}
	}

	private void firePartDeactivated(MPart part) {
		Object client = part.getObject();
		if (client instanceof CompatibilityPart) {
			IWorkbenchPart workbenchPart = ((CompatibilityPart) client).getPart();
			IWorkbenchPartReference partReference = getReference(workbenchPart);

			for (Object listener : partListenerList.getListeners()) {
				((IPartListener) listener).partDeactivated(workbenchPart);
			}

			for (Object listener : partListener2List.getListeners()) {
				((IPartListener2) listener).partDeactivated(partReference);
			}
		}
	}

	public void firePartOpened(CompatibilityPart compatibilityPart) {
		IWorkbenchPart part = compatibilityPart.getPart();
		IWorkbenchPartReference partReference = compatibilityPart.getReference();

		SaveablesList saveablesList = (SaveablesList) getWorkbenchWindow().getService(
				ISaveablesLifecycleListener.class);
		saveablesList.postOpen(part);

		for (Object listener : partListenerList.getListeners()) {
			((IPartListener) listener).partOpened(part);
		}

		for (Object listener : partListener2List.getListeners()) {
			((IPartListener2) listener).partOpened(partReference);
		}
	}

	public void firePartClosed(CompatibilityPart compatibilityPart) {
		IWorkbenchPart part = compatibilityPart.getPart();
		WorkbenchPartReference partReference = compatibilityPart.getReference();

		for (Object listener : partListenerList.getListeners()) {
			((IPartListener) listener).partClosed(part);
		}

		for (Object listener : partListener2List.getListeners()) {
			((IPartListener2) listener).partClosed(partReference);
		}

		if (part instanceof IViewPart) {
			// viewReferences.remove(partReference);
		} else {
			editorReferences.remove(partReference);
		}

		for (int i = 0; i < activationList.size(); i++) {
			if (partReference.getModel() == activationList.get(i)) {
				activationList.remove(i);
				break;
			}
		}

		updateActivePartSources(null);
		updateActiveEditorSources(null);
	}

	private void firePartBroughtToTop(MPart part) {
		Object client = part.getObject();
		if (client instanceof CompatibilityPart) {
			IWorkbenchPart workbenchPart = ((CompatibilityPart) client).getPart();
			IWorkbenchPartReference partReference = getReference(workbenchPart);

			for (Object listener : partListenerList.getListeners()) {
				((IPartListener) listener).partBroughtToTop(workbenchPart);
			}

			for (Object listener : partListener2List.getListeners()) {
				((IPartListener2) listener).partBroughtToTop(partReference);
			}
		}
	}

	// FIXME: convert me to e4 events!
	private void firePartVisible(MPart part) {
		Object client = part.getObject();
		if (client instanceof CompatibilityPart) {
			IWorkbenchPart workbenchPart = ((CompatibilityPart) client).getPart();
			IWorkbenchPartReference partReference = getReference(workbenchPart);

			for (Object listener : partListener2List.getListeners()) {
				((IPartListener2) listener).partVisible(partReference);
			}
		}
	}

	// FIXME: convert me to e4 events!
	private void firePartHidden(MPart part) {
		Object client = part.getObject();
		if (client instanceof CompatibilityPart) {
			IWorkbenchPart workbenchPart = ((CompatibilityPart) client).getPart();
			IWorkbenchPartReference partReference = getReference(workbenchPart);

			for (Object listener : partListener2List.getListeners()) {
				((IPartListener2) listener).partHidden(partReference);
			}
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.IWorkbenchPage#getEditorReuseThreshold()
	 */
	public int getEditorReuseThreshold() {
		IPreferenceStore store = WorkbenchPlugin.getDefault().getPreferenceStore();
		return store.getInt(IPreferenceConstants.REUSE_EDITORS);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.IWorkbenchPage#setEditorReuseThreshold(int)
	 */
	public void setEditorReuseThreshold(int openEditors) {
		// this is an empty implementation in 3.x, see IPageLayout's
		// setEditorReuseThreshold
	}

	/**
	 * @param fileEditorInput
	 * @param editorDescriptor
	 * @param b
	 * @param object
	 */
	public IEditorPart openEditorFromDescriptor(IEditorInput fileEditorInput,
			IEditorDescriptor editorDescriptor, final boolean activate, final IMemento editorState)
			throws PartInitException {
		if (editorDescriptor.isOpenExternal()) {
			openExternalEditor((EditorDescriptor) editorDescriptor, fileEditorInput);
			return null;
		}
		return openEditor(fileEditorInput, editorDescriptor.getId(), activate, MATCH_INPUT,
				editorState);
	}

	/**
	 * Open a specific external editor on an file based on the descriptor.
	 */
	private IEditorReference openExternalEditor(final EditorDescriptor desc, IEditorInput input)
			throws PartInitException {
		final CoreException ex[] = new CoreException[1];

		final IPathEditorInput pathInput = getPathEditorInput(input);
		if (pathInput != null && pathInput.getPath() != null) {
			BusyIndicator.showWhile(legacyWindow.getWorkbench().getDisplay(), new Runnable() {
				public void run() {
					try {
						if (desc.getLauncher() != null) {
							// open using launcher
							Object launcher = WorkbenchPlugin.createExtension(desc
									.getConfigurationElement(),
									IWorkbenchRegistryConstants.ATT_LAUNCHER);
							((IEditorLauncher) launcher).open(pathInput.getPath());
						} else {
							// open using command
							ExternalEditor oEditor = new ExternalEditor(pathInput.getPath(), desc);
							oEditor.open();
						}
					} catch (CoreException e) {
						ex[0] = e;
					}
				}
			});
		} else {
			throw new PartInitException(NLS.bind(
					WorkbenchMessages.EditorManager_errorOpeningExternalEditor, desc.getFileName(),
					desc.getId()));
		}

		if (ex[0] != null) {
			throw new PartInitException(NLS.bind(
					WorkbenchMessages.EditorManager_errorOpeningExternalEditor, desc.getFileName(),
					desc.getId()), ex[0]);
		}

		// we do not have an editor part for external editors
		return null;
	}

	private IPathEditorInput getPathEditorInput(IEditorInput input) {
		if (input instanceof IPathEditorInput)
			return (IPathEditorInput) input;
		return (IPathEditorInput) Util.getAdapter(input, IPathEditorInput.class);
	}
}
