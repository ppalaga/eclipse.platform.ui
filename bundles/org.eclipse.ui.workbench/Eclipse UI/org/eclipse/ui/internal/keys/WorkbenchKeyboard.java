/*******************************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others. All rights reserved.
 * This program and the accompanying materials are made available under the
 * terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors: IBM Corporation - initial API and implementation
 ******************************************************************************/

package org.eclipse.ui.internal.keys;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.TreeMap;
import java.util.WeakHashMap;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Widget;

import org.eclipse.jface.preference.IPreferenceStore;

import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.commands.ICommand;
import org.eclipse.ui.commands.ICommandManager;
import org.eclipse.ui.commands.NotDefinedException;
import org.eclipse.ui.keys.KeySequence;
import org.eclipse.ui.keys.KeyStroke;
import org.eclipse.ui.keys.ParseException;
import org.eclipse.ui.keys.SWTKeySupport;

import org.eclipse.ui.internal.IPreferenceConstants;
import org.eclipse.ui.internal.Workbench;
import org.eclipse.ui.internal.WorkbenchMessages;
import org.eclipse.ui.internal.WorkbenchPlugin;
import org.eclipse.ui.internal.commands.ActionHandler;
import org.eclipse.ui.internal.commands.CommandManager;
import org.eclipse.ui.internal.util.StatusLineContributionItem;
import org.eclipse.ui.internal.util.Util;

/**
 * <p>
 * Controls the keyboard input into the workbench key binding architecture.
 * This allows key events to be programmatically pushed into the key binding
 * architecture -- potentially triggering the execution of commands. It is used
 * by the <code>Workbench</code> to listen for events on the <code>Display</code>.
 * </p>
 * <p>
 * This class is not designed to be thread-safe. It is assumed that all access
 * to the <code>press</code> method is done through the event loop. Accessing
 * this method outside the event loop can cause corruption of internal state.
 * </p>
 * 
 * @since 3.0
 */
public class WorkbenchKeyboard {

	/**
	 * Whether the keyboard should kick into debugging mode. This is a local
	 * flag, that allows the debugging stuff to be compiled out.
	 */
	private static final boolean DEBUG = false;
	/**
	 * The maximum height of the multi-stroke key binding assistant shell.
	 */
	private static final int MULTI_KEY_ASSIST_SHELL_MAX_HEIGHT = 175;
	/**
	 * The maximum width of the multi-stroke key binding assistant shell.
	 */
	private static final int MULTI_KEY_ASSIST_SHELL_MAX_WIDTH = 300;
	/**
	 * The properties key for the key strokes that should be processed out of
	 * order.
	 */
	static final String OUT_OF_ORDER_KEYS = "OutOfOrderKeys"; //$NON-NLS-1$
	/** The collection of keys that are to be processed out-of-order. */
	static KeySequence outOfOrderKeys;
	/**
	 * The translation bundle in which to look up internationalized text.
	 */
	private final static ResourceBundle RESOURCE_BUNDLE =
		ResourceBundle.getBundle(WorkbenchKeyboard.class.getName());

	static {
		initializeOutOfOrderKeys();
	}

	/**
	 * Generates any key strokes that are near matches to the given event. The
	 * first such key stroke is always the exactly matching key stroke.
	 * 
	 * @param event
	 *            The event from which the key strokes should be generated;
	 *            must not be <code>null</code>.
	 * @return The set of nearly matching key strokes. It is never <code>null</code>,
	 *         but may be empty.
	 */
	public static List generatePossibleKeyStrokes(Event event) {
		final List keyStrokes = new ArrayList(3);

		/*
		 * If this is not a keyboard event, then there are no key strokes. This
		 * can happen if we are listening to focus traversal events.
		 */
		if ((event.stateMask == 0) && (event.keyCode == 0) && (event.character == 0)) {
			return keyStrokes;
		}

		// Add each unique key stroke to the list for consideration.
		final int firstAccelerator = SWTKeySupport.convertEventToUnmodifiedAccelerator(event);
		keyStrokes.add(SWTKeySupport.convertAcceleratorToKeyStroke(firstAccelerator));
		final int secondAccelerator = SWTKeySupport.convertEventToUnshiftedModifiedAccelerator(event);
		if (secondAccelerator != firstAccelerator) {
			keyStrokes.add(SWTKeySupport.convertAcceleratorToKeyStroke(secondAccelerator));
		}
		final int thirdAccelerator = SWTKeySupport.convertEventToModifiedAccelerator(event);
		if ((thirdAccelerator != secondAccelerator) && (thirdAccelerator != firstAccelerator)) {
			keyStrokes.add(SWTKeySupport.convertAcceleratorToKeyStroke(thirdAccelerator));
		}
		
		return keyStrokes;
	}

	/**
	 * Initializes the <code>outOfOrderKeys</code> member variable using the
	 * keys defined in the properties file.
	 */
	private static void initializeOutOfOrderKeys() {
		// Get the key strokes which should be out of order.
		String keysText = WorkbenchMessages.getString(OUT_OF_ORDER_KEYS);
		outOfOrderKeys = KeySequence.getInstance();
		try {
			outOfOrderKeys = KeySequence.getInstance(keysText);
		} catch (ParseException e) {
			String message = "Could not parse out-of-order keys definition: '" + keysText + "'.  Continuing with no out-of-order keys."; //$NON-NLS-1$ //$NON-NLS-2$
			WorkbenchPlugin.log(
				message,
				new Status(IStatus.ERROR, WorkbenchPlugin.PI_WORKBENCH, 0, message, e));
		}
	}

	/**
	 * <p>
	 * Determines whether the given event represents a key press that should be
	 * handled as an out-of-order event. An out-of-order key press is one that
	 * is passed to the focus control first. Only if the focus control fails to
	 * respond will the regular key bindings get applied.
	 * </p>
	 * <p>
	 * Care must be taken in choosing which keys are chosen as out-of-order
	 * keys. This method has only been designed and test to work with the
	 * unmodified "Escape" key stroke.
	 * </p>
	 * 
	 * @param keyStrokes
	 *            The key stroke in which to look for out-of-order keys; must
	 *            not be <code>null</code>.
	 * @return <code>true</code> if the key is an out-of-order key; <code>false</code>
	 *         otherwise.
	 */
	private static boolean isOutOfOrderKey(List keyStrokes) {
		// Compare to see if one of the possible key strokes is out of order.
		Iterator keyStrokeItr = keyStrokes.iterator();
		while (keyStrokeItr.hasNext()) {
			if (outOfOrderKeys.getKeyStrokes().contains(keyStrokeItr.next())) {
				return true;
			}
		}

		return false;
	}

	/**
	 * The command manager to be used to resolve key bindings. This member
	 * variable should never be <code>null</code>.
	 */
	private final ICommandManager commandManager;
	/**
	 * The listener that runs key events past the global key bindings.
	 */
	private final Listener keyDownFilter = new Listener() {
		public void handleEvent(Event event) {
			if (DEBUG) {
				System.out.println("Listener.handleEvent(type=" //$NON-NLS-1$
				+event.type + ",stateMask=0x" //$NON-NLS-1$
				+Integer.toHexString(event.stateMask) + ",keyCode=0x" //$NON-NLS-1$
				+Integer.toHexString(event.keyCode) + ",character=0x" //$NON-NLS-1$
				+Integer.toHexString(event.character) + ")"); //$NON-NLS-1$
			}
			filterKeySequenceBindings(event);
		}
	};
	/**
	 * The shells managed by the global key binding architecture. The keys are
	 * the shells themselves, and the values are a Boolean indicating whether
	 * the shell should only get dialog key bindings. As the shells are
	 * destroyed, they should be automagically cleared from this map.
	 */
	private final WeakHashMap managedShells = new WeakHashMap();
	/**
	 * The <code>Shell</code> displayed to the user to assist them in
	 * completing a multi-stroke keyboard shortcut.
	 */
	private Shell multiKeyAssistShell = null;
	/**
	 * The time at which the last timer was started. This is used to judge if a
	 * sufficient amount of time has elapsed. This is simply the output of
	 * <code>System.currentTimeMillis()</code>.
	 */
	private long startTime = Long.MAX_VALUE;
	/**
	 * The mode is the current state of the key binding architecture. In the
	 * case of multi-stroke key bindings, this can be a partially complete key
	 * binding.
	 */
	private final KeyBindingState state;
	/**
	 * The window listener responsible for maintaining internal state as the
	 * focus moves between windows on the desktop.
	 */
	private final IWindowListener windowListener = new IWindowListener() {
		/*
		 * (non-Javadoc)
		 * 
		 * @see org.eclipse.ui.IWindowListener#windowActivated(org.eclipse.ui.IWorkbenchWindow)
		 */
		public void windowActivated(IWorkbenchWindow window) {
			checkActiveWindow(window);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see org.eclipse.ui.IWindowListener#windowClosed(org.eclipse.ui.IWorkbenchWindow)
		 */
		public void windowClosed(IWorkbenchWindow window) {
			// Do nothing.
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see org.eclipse.ui.IWindowListener#windowDeactivated(org.eclipse.ui.IWorkbenchWindow)
		 */
		public void windowDeactivated(IWorkbenchWindow window) {
			// Do nothing
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see org.eclipse.ui.IWindowListener#windowOpened(org.eclipse.ui.IWorkbenchWindow)
		 */
		public void windowOpened(IWorkbenchWindow window) {
			// Do nothing.
		}
	};
	/**
	 * The workbench on which this keyboard interface should act.
	 */
	private final IWorkbench workbench;

	/**
	 * Constructs a new instance of <code>WorkbenchKeyboard</code> associated
	 * with a particular workbench.
	 * 
	 * @param associatedWorkbench
	 *            The workbench with which this keyboard interface should work;
	 *            must not be <code>null</code>.
	 * @param associatedCommandManager
	 *            The command manager to be used by this keyboard interface;
	 *            must not be <code>null</code>.
	 */
	public WorkbenchKeyboard(
		Workbench associatedWorkbench,
		ICommandManager associatedCommandManager) {
		workbench = associatedWorkbench;
		state = new KeyBindingState(associatedWorkbench);
		commandManager = associatedCommandManager;

		workbench.addWindowListener(windowListener);
	}

	/**
	 * Verifies that the active workbench window is the same as the workbench
	 * window associated with the state. This is used to verify that the state
	 * is properly reset as focus changes. When they are not the same, the
	 * state is reset and associated with the newly activated window.
	 * 
	 * @param window
	 *            The activated window; must not be <code>null</code>.
	 */
	private void checkActiveWindow(IWorkbenchWindow window) {
		if (!window.equals(state.getAssociatedWindow())) {
			resetState();
			state.setAssociatedWindow(window);
		}
	}

	/**
	 * Closes the multi-stroke key binding assistant shell, if it exists and
	 * isn't already disposed.
	 */
	private void closeMultiKeyAssistShell() {
		if ((multiKeyAssistShell != null) && (!multiKeyAssistShell.isDisposed())) {
			deregister(multiKeyAssistShell);
			multiKeyAssistShell.close();
			multiKeyAssistShell.dispose();
			multiKeyAssistShell = null;
		}

	}

	/**
	 * Deregisters the given <code>shell</code> from this keyboard shortcut
	 * manager. This is not stictly necessary, as the internal storage is a
	 * weak hash map. However, it is good practice.
	 * 
	 * @param shell
	 *            The shell to deregister from key bindings; may be <code>null</code>.
	 */
	public void deregister(Shell shell) {
		managedShells.remove(shell);
	}

	/**
	 * Performs the actual execution of the command by looking up the current
	 * handler from the command manager. If there is a handler and it is
	 * enabled, then it tries the actual execution. Execution failures are
	 * logged. When this method completes, the key binding state is reset.
	 * 
	 * @param commandId
	 *            The identifier for the command that should be executed;
	 *            should not be <code>null</code>.
	 * @param event
	 *            The event triggering the execution. This is needed for
	 *            backwards compatability and might be removed in the future.
	 *            This value should not be <code>null</code>.
	 * @return <code>true</code> if there was a handler; <code>false</code>
	 *         otherwise.
	 */
	private boolean executeCommand(String commandId, Event event) {
		if (DEBUG) {
			System.out.println("WorkbenchKeyboard.executeCommand(commandId=" + commandId + ")"); //$NON-NLS-1$ //$NON-NLS-2$
		}

		// Reset the key binding state (close window, clear status line, etc.)
		resetState();

		// Dispatch to the handler.
		Map actionsById = ((CommandManager) commandManager).getActionsById();
		org.eclipse.ui.commands.IHandler action =
			(org.eclipse.ui.commands.IHandler) actionsById.get(commandId);

		if (DEBUG) {
			if (action == null) {
				System.out.println("\taction=null"); //$NON-NLS-1$
			} else {
				System.out.println("\taction=" + ((ActionHandler) action).getAction()); //$NON-NLS-1$
				System.out.println("\taction.isEnabled()=" + action.isEnabled()); //$NON-NLS-1$
			}
		}

		if (action != null && action.isEnabled()) {
			try {
				action.execute(event);
			} catch (Exception e) {
				String message = "Action for command '" + commandId + "' failed to execute properly."; //$NON-NLS-1$ //$NON-NLS-2$
				WorkbenchPlugin.log(
					message,
					new Status(IStatus.ERROR, WorkbenchPlugin.PI_WORKBENCH, 0, message, e));
			}
		}

		return (action != null);
	}

	/**
	 * <p>
	 * Launches the command matching a the typed key. This filter an incoming
	 * <code>SWT.KeyDown</code> or <code>SWT.Traverse</code> event at the
	 * level of the display (i.e., before it reaches the widgets). It does not
	 * allow processing in a dialog or if the key strokes does not contain a
	 * natural key.
	 * </p>
	 * <p>
	 * Some key strokes (defined as a property) are declared as out-of-order
	 * keys. This means that they are processed by the widget <em>first</em>.
	 * Only if the other widget listeners do no useful work does it try to
	 * process key bindings. For example, "ESC" can cancel the current widget
	 * action, if there is one, without triggering key bindings.
	 * </p>
	 * 
	 * @param event
	 *            The incoming event; must not be <code>null</code>.
	 */
	private void filterKeySequenceBindings(Event event) {
		/*
		 * Only process key strokes containing natural keys to trigger key
		 * bindings.
		 */
		if ((event.keyCode & SWT.MODIFIER_MASK) != 0)
			return;

		/*
		 * There are three classes of shells: fully-managed, partially-managed,
		 * and unmanaged. An unmanaged shell is a shell with no parent that has
		 * not registered; it gets no key bindings. A partially-managed shell
		 * is either a shell with a parent, or it is a shell with no parent
		 * that has registered for partial service; it gets dialog key
		 * bindings. A fully0managed shell has no parent and has registered for
		 * full service; they get all key bindings.
		 */
		boolean dialogOnly = false;
		if (event.widget instanceof Control) {
			Shell shell = ((Control) event.widget).getShell();
			Boolean dialog = (Boolean) managedShells.get(shell);
			if (dialog == null) {
				// The shell has not been registered.
				if ((shell != null) && (shell.getParent() != null)) {
					// There is a parent shell. Partially-managed.
					dialogOnly = true;
				} else {
					// There is no parent shell. The shell is unmanaged.
					return;
				}

			} else if (dialog.booleanValue()) {
				// The window is managed, but requested partial management.
				dialogOnly = true;

			} else {
				// The window is fully-managed; leave dialogOnly=false.

			}
		}

		// Allow special key out-of-order processing.
		List keyStrokes = generatePossibleKeyStrokes(event);
		if (isOutOfOrderKey(keyStrokes)) {
			if (event.type == SWT.KeyDown) {
				Widget widget = event.widget;
				if (widget instanceof StyledText) {
					/*
					 * KLUDGE. Some people try to do useful work in verify
					 * listeners. The way verify listeners work in SWT, we need
					 * to verify the key as well; otherwise, we can detect that
					 * useful work has been done.
					 */
					((StyledText) widget).addVerifyKeyListener(
						new OutOfOrderVerifyListener(new OutOfOrderListener(this, dialogOnly)));
				} else {
					widget.addListener(SWT.KeyDown, new OutOfOrderListener(this, dialogOnly));
				}
			}
			/*
			 * Otherwise, we count on a key down arriving eventually. Expecting
			 * out of order handling on Ctrl+Tab, for example, is a bad idea
			 * (stick to keys that are not window traversal keys).
			 */
		} else {
			processKeyEvent(keyStrokes, event, dialogOnly);
		}
	}

	/**
	 * An accessor for the filter that processes key down and traverse events
	 * on the display.
	 * 
	 * @return The global key down and traverse filter; never <code>null</code>.
	 */
	public Listener getKeyDownFilter() {
		return keyDownFilter;
	}

	/**
	 * Determines whether the key sequence is a perfect match for any command.
	 * If there is a match, then the corresponding command identifier is
	 * returned.
	 * 
	 * @param keySequence
	 *            The key sequence to check for a match; must never be <code>null</code>.
	 * @return The command identifier for the perfectly matching command;
	 *         <code>null</code> if no command matches.
	 */
	private String getPerfectMatch(KeySequence keySequence) {
		return commandManager.getPerfectMatch(keySequence);
	}

	/**
	 * Changes the key binding state to the given value. This should be an
	 * incremental change, but there are no checks to guarantee this is so. It
	 * also sets up a <code>Shell</code> to be displayed after one second has
	 * elapsed. This shell will show the user the possible completions for what
	 * they have typed.
	 * 
	 * @param sequence
	 *            The new key sequence for the state; should not be <code>null</code>.
	 */
	private void incrementState(KeySequence sequence) {
		// Record the starting time.
		startTime = System.currentTimeMillis();
		final long myStartTime = startTime;

		// Update the state.
		state.setCurrentSequence(sequence);
		state.setAssociatedWindow(workbench.getActiveWorkbenchWindow());

		// After 1s, open a shell displaying the possible completions.
		final IPreferenceStore store = WorkbenchPlugin.getDefault().getPreferenceStore();
		if (store.getBoolean(IPreferenceConstants.MULTI_KEY_ASSIST)) {
			final Display display = workbench.getDisplay();
			final int delay = store.getInt(IPreferenceConstants.MULTI_KEY_ASSIST_TIME);
			display.timerExec(delay, new Runnable() {
				public void run() {
					if ((System.currentTimeMillis() > (myStartTime - delay))
						&& (startTime == myStartTime)) {
						openMultiKeyAssistShell(display);
					}
				}
			});
		}
	}

	/**
	 * Determines whether the key sequence partially matches on of the active
	 * key bindings.
	 * 
	 * @param keySequence
	 *            The key sequence to check for a partial match; must never be
	 *            <code>null</code>.
	 * @return <code>true</code> if there is a partial match; <code>false</code>
	 *         otherwise.
	 */
	private boolean isPartialMatch(KeySequence keySequence) {
		return commandManager.isPartialMatch(keySequence);
	}

	/**
	 * Determines whether the key sequence perfectly matches on of the active
	 * key bindings.
	 * 
	 * @param keySequence
	 *            The key sequence to check for a perfect match; must never be
	 *            <code>null</code>.
	 * @return <code>true</code> if there is a perfect match; <code>false</code>
	 *         otherwise.
	 */
	private boolean isPerfectMatch(KeySequence keySequence) {
		return commandManager.isPerfectMatch(keySequence);
	}

	/**
	 * Opens a <code>Shell</code> to assist the user in completing a
	 * multi-stroke key binding. After this method completes, <code>multiKeyAssistShell</code>
	 * should point at the newly opened window.
	 * 
	 * @param display
	 *            The display on which the shell should be opened; must not be
	 *            <code>null</code>.
	 */
	private void openMultiKeyAssistShell(final Display display) {
		// Safety check to close an already open shell, if there is one.
		if (multiKeyAssistShell != null) {
			multiKeyAssistShell.close();
		}

		// Get the status line. If none, then abort.
		StatusLineContributionItem statusLine = state.getStatusLine();
		if (statusLine == null) {
			return;
		}
		Point statusLineLocation = statusLine.getDisplayLocation();
		if (statusLineLocation == null) {
			return;
		}

		// Set up the shell.
		multiKeyAssistShell = new Shell(display, SWT.NO_TRIM);
		GridLayout layout = new GridLayout();
		layout.marginHeight = 0;
		layout.marginWidth = 0;
		multiKeyAssistShell.setLayout(layout);
		multiKeyAssistShell.setBackground(display.getSystemColor(SWT.COLOR_INFO_BACKGROUND));

		// Get the list of items.
		Map partialMatches = new TreeMap(new Comparator() {
			public int compare(Object a, Object b) {
				KeySequence sequenceA = (KeySequence) a;
				KeySequence sequenceB = (KeySequence) b;
				return sequenceA.format().compareTo(sequenceB.format());
			}
		});
		partialMatches.putAll(commandManager.getPartialMatches(state.getCurrentSequence()));
		Iterator partialMatchItr = partialMatches.entrySet().iterator();
		while (partialMatchItr.hasNext()) {
			Map.Entry entry = (Map.Entry) partialMatchItr.next();
			String commandId = (String) entry.getValue();
			ICommand command = commandManager.getCommand(commandId);
			// TODO The enabled property of ICommand is broken.
			if (!command.isDefined() || !command.isActive() // ||
			// !command.isEnabled()
			) {
				partialMatchItr.remove();
			}
		}

		// Layout the partial matches.
		if (partialMatches.isEmpty()) {
			Label noMatchesLabel = new Label(multiKeyAssistShell, SWT.NULL);
			noMatchesLabel.setText(Util.translateString(RESOURCE_BUNDLE, "NoMatches.Message")); //$NON-NLS-1$
			noMatchesLabel.setLayoutData(new GridData(GridData.FILL_BOTH));
			noMatchesLabel.setBackground(multiKeyAssistShell.getBackground());
		} else {
			// Layout the table.
			final Table completionsTable = new Table(multiKeyAssistShell, SWT.SINGLE);
			completionsTable.setBackground(multiKeyAssistShell.getBackground());
			GridData gridData = new GridData(GridData.FILL_BOTH);
			completionsTable.setLayoutData(gridData);

			// Initialize the columns and rows.
			final List commands = new ArrayList(); // remember commands
			TableColumn columnKeySequence = new TableColumn(completionsTable, SWT.LEFT, 0);
			TableColumn columnCommandName = new TableColumn(completionsTable, SWT.LEFT, 1);
			Iterator itemsItr = partialMatches.entrySet().iterator();
			while (itemsItr.hasNext()) {
				Map.Entry entry = (Map.Entry) itemsItr.next();
				KeySequence sequence = (KeySequence) entry.getKey();
				String commandId = (String) entry.getValue();
				ICommand command = commandManager.getCommand(commandId);
				try {
					String[] text = { sequence.format(), command.getName()};
					TableItem item = new TableItem(completionsTable, SWT.NULL);
					item.setText(text);
					commands.add(command);
				} catch (NotDefinedException e) {
					// Not much to do, but this shouldn't really happen.
				}
			}
			columnKeySequence.pack();
			columnCommandName.pack();

			// If you double-click on the table, it should execute the selected
			// command.
			completionsTable.addSelectionListener(new SelectionListener() {
				public void widgetDefaultSelected(SelectionEvent e) {
					int selectionIndex = completionsTable.getSelectionIndex();
					if (selectionIndex >= 0) {
						ICommand command = (ICommand) commands.get(selectionIndex);
						executeCommand(command.getId(), new Event());
					}
				}

				public void widgetSelected(SelectionEvent e) {
					// Do nothing
				}
			});
		}

		// Size the shell.
		multiKeyAssistShell.pack();
		Point assistShellSize = multiKeyAssistShell.getSize();
		if (assistShellSize.x > MULTI_KEY_ASSIST_SHELL_MAX_WIDTH) {
			assistShellSize.x = MULTI_KEY_ASSIST_SHELL_MAX_WIDTH;
		}
		if (assistShellSize.y > MULTI_KEY_ASSIST_SHELL_MAX_HEIGHT) {
			assistShellSize.y = MULTI_KEY_ASSIST_SHELL_MAX_HEIGHT;
		}
		multiKeyAssistShell.setSize(assistShellSize);

		// Position the shell.
		Point assistShellLocation =
			new Point(statusLineLocation.x, statusLineLocation.y - assistShellSize.y);
		Rectangle displayBounds = display.getBounds();
		final int displayRightEdge = displayBounds.x + displayBounds.width;
		if (assistShellLocation.x < displayBounds.x) {
			assistShellLocation.x = displayBounds.x;
		} else if ((assistShellLocation.x + assistShellSize.x) > displayRightEdge) {
			assistShellLocation.x = displayRightEdge - assistShellSize.x;
		}
		final int displayBottomEdge = displayBounds.y + displayBounds.height;
		if (assistShellLocation.y < displayBounds.y) {
			assistShellLocation.y = displayBounds.y;
		} else if ((assistShellLocation.y + assistShellSize.y) > displayBottomEdge) {
			assistShellLocation.y = displayBottomEdge - assistShellSize.y;
		}
		multiKeyAssistShell.setLocation(assistShellLocation);

		// If the shell loses focus, it should be closed.
		multiKeyAssistShell.addListener(SWT.Deactivate, new Listener() {
			public void handleEvent(Event event) {
				closeMultiKeyAssistShell();
			}
		});

		// Open the shell.
		register(multiKeyAssistShell, false);
		multiKeyAssistShell.open();
	}

	/**
	 * Processes a key press with respect to the key binding architecture. This
	 * updates the mode of the command manager, and runs the current handler
	 * for the command that matches the key sequence, if any.
	 * 
	 * @param potentialKeyStrokes
	 *            The key strokes that could potentially match, in the order of
	 *            priority; must not be <code>null</code>.
	 * @param event
	 *            The event to pass to the action; may be <code>null</code>.
	 * @param dialogOnly
	 *            Whether the key sequence should only be allowed to match on
	 *            dialog commands.
	 * @return <code>true</code> if a command is executed; <code>false</code>
	 *         otherwise.
	 */
	public boolean press(List potentialKeyStrokes, Event event, boolean dialogOnly) {
		// TODO remove event parameter once key-modified actions are removed
		if (DEBUG) {
			System.out.println("WorkbenchKeyboard.press(potentialKeyStrokes=" //$NON-NLS-1$
			+potentialKeyStrokes + ",dialogOnly=" //$NON-NLS-1$
			+dialogOnly + ")"); //$NON-NLS-1$
		}
		// TODO Add proper dialog support
		if (dialogOnly) {
			return false;
		}

		KeySequence sequenceBeforeKeyStroke = state.getCurrentSequence();
		for (Iterator iterator = potentialKeyStrokes.iterator(); iterator.hasNext();) {
			KeySequence sequenceAfterKeyStroke =
				KeySequence.getInstance(sequenceBeforeKeyStroke, (KeyStroke) iterator.next());

			if (isPartialMatch(sequenceAfterKeyStroke)) {
				incrementState(sequenceAfterKeyStroke);
				return true;
			} else if (isPerfectMatch(sequenceAfterKeyStroke)) {
				String commandId = getPerfectMatch(sequenceAfterKeyStroke);
				return (executeCommand(commandId, event) || sequenceBeforeKeyStroke.isEmpty());
			} else if (
				(multiKeyAssistShell != null)
					&& ((event.keyCode == SWT.ARROW_DOWN)
						|| (event.keyCode == SWT.ARROW_UP)
						|| (event.keyCode == SWT.ARROW_LEFT)
						|| (event.keyCode == SWT.ARROW_RIGHT)
						|| (event.keyCode == SWT.CR))) {
				// We don't want to swallow keyboard navigation keys.
				return false;
			}
		}

		resetState();
		return false;
	}

	/**
	 * <p>
	 * Actually performs the processing of the key event by interacting with
	 * the <code>ICommandManager</code>. If work is carried out, then the
	 * event is stopped here (i.e., <code>event.doit = false</code>). It
	 * does not do any processing if there are no matching key strokes.
	 * </p>
	 * <p>
	 * If the active <code>Shell</code> is not the same as the one to which
	 * the state is associated, then a reset occurs.
	 * </p>
	 * 
	 * @param keyStrokes
	 *            The set of all possible matching key strokes; must not be
	 *            <code>null</code>.
	 * @param event
	 *            The event to process; must not be <code>null</code>.
	 * @param dialogOnly
	 *            Whether the key bindings should only be allowed to match on
	 *            dialog commands
	 */
	void processKeyEvent(List keyStrokes, Event event, boolean dialogOnly) {
		// Dispatch the keyboard shortcut, if any.
		if ((!keyStrokes.isEmpty()) && (press(keyStrokes, event, dialogOnly))) {
			switch (event.type) {
				case SWT.KeyDown :
					event.doit = false;
					break;
				case SWT.Traverse :
					event.detail = SWT.TRAVERSE_NONE;
					event.doit = true;
					break;
				default :
					}

			event.type = SWT.NONE;
		}
	}

	/**
	 * Registers the given <code>shell</code> with this keyboard shortcut
	 * manager -- indicating whether it should receive dialog key bindings or
	 * the full set of key bindings. Deregistration is handled automatically by
	 * the use of a weak hash map.
	 * 
	 * @param shell
	 *            The shell to register for key bindings; may be <code>null</code>.
	 * @param dialogOnly
	 *            Whether the shell should only receive key bindings particular
	 *            to a dialog.
	 */
	public void register(Shell shell, boolean dialogOnly) {
		managedShells.put(shell, dialogOnly ? Boolean.TRUE : Boolean.FALSE);
	}

	/**
	 * Resets the state, and cancels any running timers. If there is a <code>Shell</code>
	 * currently open, then it closes it.
	 */
	private void resetState() {
		startTime = Long.MAX_VALUE;
		state.reset();
		closeMultiKeyAssistShell();
	}
}
