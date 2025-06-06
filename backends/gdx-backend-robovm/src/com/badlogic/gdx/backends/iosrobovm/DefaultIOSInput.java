/*******************************************************************************
 * Copyright 2011 See AUTHORS file.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.badlogic.gdx.backends.iosrobovm;

import com.badlogic.gdx.AbstractInput;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.backends.iosrobovm.custom.UIAcceleration;
import com.badlogic.gdx.backends.iosrobovm.custom.UIAccelerometer;
import com.badlogic.gdx.backends.iosrobovm.custom.UIAccelerometerDelegate;
import com.badlogic.gdx.backends.iosrobovm.custom.UIAccelerometerDelegateAdapter;
import com.badlogic.gdx.graphics.glutils.HdpiMode;
import com.badlogic.gdx.input.NativeInputConfiguration;
import com.badlogic.gdx.input.TextInputWrapper;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.Pool;

import org.robovm.apple.coregraphics.CGPoint;
import org.robovm.apple.coregraphics.CGRect;
import org.robovm.apple.coregraphics.CGSize;
import org.robovm.apple.foundation.*;
import org.robovm.apple.gamecontroller.GCKeyboard;
import org.robovm.apple.uikit.*;
import org.robovm.objc.Selector;
import org.robovm.objc.annotation.Method;
import org.robovm.objc.block.VoidBlock1;
import org.robovm.rt.VM;
import org.robovm.rt.bro.NativeObject;
import org.robovm.rt.bro.annotation.MachineSizedUInt;
import org.robovm.rt.bro.annotation.Pointer;

public class DefaultIOSInput extends AbstractInput implements IOSInput {
	static final int MAX_TOUCHES = 20;
	private static final int POINTER_NOT_FOUND = -1;

	private static class NSObjectWrapper<T extends NSObject> {
		private static final long HANDLE_OFFSET;
		static {
			try {
				HANDLE_OFFSET = VM.getInstanceFieldOffset(VM.getFieldAddress(NativeObject.class.getDeclaredField("handle")));
			} catch (Throwable t) {
				throw new Error(t);
			}
		}

		private final T instance;

		public NSObjectWrapper (Class<T> cls) {
			instance = VM.allocateObject(cls);
		}

		public T wrap (long handle) {
			VM.setLong(VM.getObjectAddress(instance) + HANDLE_OFFSET, handle);
			return instance;
		}
	}

	private static final NSObjectWrapper<UITouch> UI_TOUCH_WRAPPER = new NSObjectWrapper<UITouch>(UITouch.class);
	static final NSObjectWrapper<UIAcceleration> UI_ACCELERATION_WRAPPER = new NSObjectWrapper<UIAcceleration>(
		UIAcceleration.class);

	IOSApplication app;
	IOSApplicationConfiguration config;
	int[] deltaX = new int[MAX_TOUCHES];
	int[] deltaY = new int[MAX_TOUCHES];
	int[] touchX = new int[MAX_TOUCHES];
	int[] touchY = new int[MAX_TOUCHES];
	float[] pressures = new float[MAX_TOUCHES];
	boolean pressureSupported;
	// we store the pointer to the UITouch struct here, or 0
	long[] touchDown = new long[MAX_TOUCHES];
	int numTouched = 0;
	boolean justTouched = false;
	Pool<TouchEvent> touchEventPool = new Pool<TouchEvent>() {
		@Override
		protected TouchEvent newObject () {
			return new TouchEvent();
		}
	};
	Array<TouchEvent> touchEvents = new Array<TouchEvent>();
	private final Pool<KeyEvent> keyEventPool = new Pool<KeyEvent>(16, 1000) {
		protected KeyEvent newObject () {
			return new KeyEvent();
		}
	};
	private final Array<KeyEvent> keyEvents = new Array();
	private long currentEventTimeStamp = 0;
	float[] acceleration = new float[3];
	float[] rotation = new float[3];
	float[] R = new float[9];
	InputProcessor inputProcessor = null;

	private IOSHaptics haptics;
	// CMMotionManager motionManager;
	protected UIAccelerometerDelegate accelerometerDelegate;
	boolean compassSupported;
	boolean keyboardCloseOnReturn;
	boolean softkeyboardActive = false;

	private boolean hadHardwareKeyEvent = false;

	public DefaultIOSInput (IOSApplication app) {
		this.app = app;
		this.config = app.config;
		this.keyboardCloseOnReturn = app.config.keyboardCloseOnReturn;
	}

	@Override
	public void setupPeripherals () {
		// motionManager = new CMMotionManager();
		setupAccelerometer();
		setupCompass();
		setupHaptics();
		setupPressure();
	}

	protected void setupCompass () {
		if (config.useCompass) {
			// setupMagnetometer();
		}
	}

	protected void setupAccelerometer () {
		if (config.useAccelerometer) {
			accelerometerDelegate = new UIAccelerometerDelegateAdapter() {

				@Method(selector = "accelerometer:didAccelerate:")
				public void didAccelerate (UIAccelerometer accelerometer, @Pointer long valuesPtr) {
					UIAcceleration values = UI_ACCELERATION_WRAPPER.wrap(valuesPtr);
					float x = (float)values.getX() * 10;
					float y = (float)values.getY() * 10;
					float z = (float)values.getZ() * 10;

					acceleration[0] = -x;
					acceleration[1] = -y;
					acceleration[2] = -z;
				}
			};
			UIAccelerometer.getSharedAccelerometer().setDelegate(accelerometerDelegate);
			UIAccelerometer.getSharedAccelerometer().setUpdateInterval(config.accelerometerUpdate);
		}
	}

	protected void setupHaptics () {
		haptics = new IOSHaptics(config.useHaptics);
	}

	protected void setupPressure () {
		UIForceTouchCapability forceTouchCapability = UIScreen.getMainScreen().getTraitCollection().getForceTouchCapability();
		pressureSupported = forceTouchCapability == UIForceTouchCapability.Available;
	}

	// need to retain a reference so GC doesn't get right of the
	// object passed to the native thread
// VoidBlock2<CMAccelerometerData, NSError> accelVoid = null;
// private void setupAccelerometer () {
// if (config.useAccelerometer) {
// motionManager.setAccelerometerUpdateInterval(config.accelerometerUpdate);
// accelVoid = new VoidBlock2<CMAccelerometerData, NSError>() {
// @Override
// public void invoke(CMAccelerometerData accelData, NSError error) {
// updateAccelerometer(accelData);
// }
// };
// motionManager.startAccelerometerUpdates(new NSOperationQueue(), accelVoid);
// }
// }

	// need to retain a reference so GC doesn't get right of the
	// object passed to the native thread
// VoidBlock2<CMMagnetometerData, NSError> magnetVoid = null;
// private void setupMagnetometer () {
// if (motionManager.isMagnetometerAvailable() && config.useCompass) compassSupported = true;
// else return;
// motionManager.setMagnetometerUpdateInterval(config.magnetometerUpdate);
// magnetVoid = new VoidBlock2<CMMagnetometerData, NSError>() {
// @Override
// public void invoke(CMMagnetometerData magnetData, NSError error) {
// updateRotation(magnetData);
// }
// };
// motionManager.startMagnetometerUpdates(new NSOperationQueue(), magnetVoid);
// }

// private void updateAccelerometer (CMAccelerometerData data) {
// float x = (float) data.getAcceleration().x() * 10f;
// float y = (float) data.getAcceleration().y() * 10f;
// float z = (float) data.getAcceleration().z() * 10f;
// acceleration[0] = -x;
// acceleration[1] = -y;
// acceleration[2] = -z;
// }
//
// private void updateRotation (CMMagnetometerData data) {
// final float eX = (float) data.getMagneticField().x();
// final float eY = (float) data.getMagneticField().y();
// final float eZ = (float) data.getMagneticField().z();
//
// float gX = acceleration[0];
// float gY = acceleration[1];
// float gZ = acceleration[2];
//
// float cX = eY * gZ - eZ * gY;
// float cY = eZ * gX - eX * gZ;
// float cZ = eX * gY - eY * gX;
//
// final float normal = (float) Math.sqrt(cX * cX + cY * cY + cZ * cZ);
// final float invertC = 1.0f / normal;
// cX *= invertC;
// cY *= invertC;
// cZ *= invertC;
// final float invertG = 1.0f / (float) Math.sqrt(gX * gX + gY * gY + gZ * gZ);
// gX *= invertG;
// gY *= invertG;
// gZ *= invertG;
// final float mX = gY * cZ - gZ * cY;
// final float mY = gZ * cX - gX * cZ;
// final float mZ = gX * cY - gY * cX;
//
// R[0] = cX; R[1] = cY; R[2] = cZ;
// R[3] = mX; R[4] = mY; R[5] = mZ;
// R[6] = gX; R[7] = gY; R[8] = gZ;
//
// rotation[0] = (float) Math.atan2(R[1], R[4]) * MathUtils.radDeg;
// rotation[1] = (float) Math.asin(-R[7]) * MathUtils.radDeg;
// rotation[2] = (float) Math.atan2(-R[6], R[8]) * MathUtils.radDeg;
// }

	@Override
	public float getAccelerometerX () {
		return acceleration[0];
	}

	@Override
	public float getAccelerometerY () {
		return acceleration[1];
	}

	@Override
	public float getAccelerometerZ () {
		return acceleration[2];
	}

	@Override
	public float getAzimuth () {
		if (!compassSupported) return 0;
		return rotation[0];
	}

	@Override
	public float getPitch () {
		if (!compassSupported) return 0;
		return rotation[1];
	}

	@Override
	public float getRoll () {
		if (!compassSupported) return 0;
		return rotation[2];
	}

	@Override
	public void getRotationMatrix (float[] matrix) {
		if (matrix.length != 9) return;
		// TODO implement when azimuth is fixed
	}

	@Override
	public int getMaxPointers () {
		return MAX_TOUCHES;
	}

	@Override
	public int getX () {
		return touchX[0];
	}

	@Override
	public int getX (int pointer) {
		return touchX[pointer];
	}

	@Override
	public int getDeltaX () {
		return deltaX[0];
	}

	@Override
	public int getDeltaX (int pointer) {
		return deltaX[pointer];
	}

	@Override
	public int getY () {
		return touchY[0];
	}

	@Override
	public int getY (int pointer) {
		return touchY[pointer];
	}

	@Override
	public int getDeltaY () {
		return deltaY[0];
	}

	@Override
	public int getDeltaY (int pointer) {
		return deltaY[pointer];
	}

	@Override
	public boolean isTouched () {
		for (int pointer = 0; pointer < MAX_TOUCHES; pointer++) {
			if (touchDown[pointer] != 0) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean justTouched () {
		return justTouched;
	}

	@Override
	public boolean isTouched (int pointer) {
		return touchDown[pointer] != 0;
	}

	@Override
	public float getPressure () {
		return pressures[0];
	}

	@Override
	public float getPressure (int pointer) {
		return pressures[pointer];
	}

	@Override
	public boolean isButtonPressed (int button) {
		return button == Buttons.LEFT && numTouched > 0;
	}

	@Override
	public boolean isButtonJustPressed (int button) {
		return button == Buttons.LEFT && justTouched;
	}

	@Override
	public void getTextInput (TextInputListener listener, String title, String text, String hint) {
		getTextInput(listener, title, text, hint, OnscreenKeyboardType.Default);
	}

	@Override
	public void getTextInput (TextInputListener listener, String title, String text, String hint, OnscreenKeyboardType type) {
		UIAlertController uiAlertController = buildUIAlertController(listener, title, text, hint, type);
		app.getUIViewController().presentViewController(uiAlertController, true, null);
	}

	// hack for software keyboard support
	// uses a hidden textfield to capture input
	// see: https://web.archive.org/web/20171016192705/http://www.badlogicgames.com/forum/viewtopic.php?f=17&t=11788

	private UIView textfield = null;
	private UITableView suggestionTable;
	private Input.InputStringValidator inputStringValidator = null;
	private String placeHolder = "";
	private TextInputWrapper textInputWrapper;
	private Integer maxTextLength;

	private final UITextViewDelegate textViewDelegate = new UITextViewDelegateAdapter() {
		@Override
		public void didChange (UITextView textView) {
			if (textView.getText().isEmpty()) {
				textView.setText(placeHolder);
				textView.setTextColor(UIColor.lightGray());
				textView
					.setSelectedTextRange(textView.getTextRange(textView.getBeginningOfDocument(), textView.getBeginningOfDocument()));
			}
		}

		@Override
		public boolean shouldChangeCharacters (UITextView textView, NSRange range, String text) {
			if (textView.getTextColor().isEqual(UIColor.lightGray())) {
				if (text.length() != 0) {
					textView.setText("");
					textView.setTextColor(UIColor.black());
				} else {
					return false;
				}
			}
			if (maxTextLength != null && textView.getText().length() + (text.length() - range.getLength()) > maxTextLength) {
				return false;
			}

			if (inputStringValidator == null) return true;
			return inputStringValidator.validate(text);
		}
	};

	private final UITextFieldDelegate textDelegate = new UITextFieldDelegateAdapter() {

		@Override
		public boolean shouldChangeCharacters (UITextField textField, NSRange range, String text) {
			if (maxTextLength != null && textField.getText().length() + (text.length() - range.getLength()) > maxTextLength) {
				return false;
			}
			if (inputStringValidator == null) return true;
			return inputStringValidator.validate(text);
		}

		@Override
		public boolean shouldReturn (UITextField textField) {
			if (keyboardCloseOnReturn) Gdx.input.closeTextInputField(true);
			Gdx.graphics.requestRendering();
			return false;
		}
	};

	private final UITextFieldDelegate textDelegateInvisible = new UITextFieldDelegateAdapter() {
		@Override
		public boolean shouldChangeCharacters (UITextField textField, NSRange range, String string) {
			for (int i = 0; i < range.getLength(); i++) {
				inputProcessor.keyTyped((char)8);
			}

			if (string.isEmpty()) {
				if (range.getLength() > 0) Gdx.graphics.requestRendering();
				return false;
			}

			char[] chars = new char[string.length()];
			string.getChars(0, string.length(), chars, 0);

			for (int i = 0; i < chars.length; i++) {
				inputProcessor.keyTyped(chars[i]);
			}
			Gdx.graphics.requestRendering();

			return true;
		}

		@Override
		public boolean shouldEndEditing (UITextField textField) {
			// Text field needs to have at least one symbol - so we can use backspace
			textField.setText("x");
			Gdx.graphics.requestRendering();

			return true;
		}

		@Override
		public boolean shouldReturn (UITextField textField) {
			if (keyboardCloseOnReturn) setOnscreenKeyboardVisible(false);
			inputProcessor.keyDown(Keys.ENTER);
			inputProcessor.keyTyped((char)13);
			Gdx.graphics.requestRendering();
			return false;
		}
	};

	@Override
	public void setOnscreenKeyboardVisible (boolean visible) {
		setOnscreenKeyboardVisible(visible, OnscreenKeyboardType.Default);
	}

	@Override
	public void setOnscreenKeyboardVisible (boolean visible, OnscreenKeyboardType type) {
		if (textfield != null && !textfield.isHidden())
			throw new RuntimeException("Can't open KeyBoard, if TextInputField KeyBoard is already open");
		// Maybe supporting needsDoneToolbar also here?
		if (textfield == null) createDefaultTextField(false, false);
		softkeyboardActive = visible;
		if (visible) {
			UITextField uiTextField = (UITextField)textfield;
			if (type == null) type = OnscreenKeyboardType.Default;
			uiTextField.setKeyboardType(getIosInputType(type));
			uiTextField.setAutocorrectionType(UITextAutocorrectionType.No);
			uiTextField.setSpellCheckingType(UITextSpellCheckingType.No);
			textfield.reloadInputViews();
			textfield.becomeFirstResponder();
			uiTextField.setDelegate(textDelegateInvisible);
			uiTextField.setText("x");
		} else {
			textfield.resignFirstResponder();
			textfield.removeFromSuperview();
			textfield.dispose();
			textfield = null;
		}
	}

	static class UtilityCallback extends NSObject {

		@Method(selector = "doneClicked")
		public void doneClicked () {
			Gdx.input.closeTextInputField(true);
		}

		@Method(selector = "doneClicked")
		public void togglePasswordView (UIButton sender) {
			// TODO: 24.11.22 This is silly, but idk how to do reasonable better
			UITextField field = (UITextField)((DefaultIOSInput)Gdx.input).getActiveKeyboardTextField();
			field.setSecureTextEntry(!field.isSecureTextEntry());
			String fileName = field.isSecureTextEntry() ? "ic_password_visible.png" : "ic_password_invisible.png";
			byte[] data = Gdx.files.classpath(fileName).readBytes();
			NSData nsData = new NSData(data);
			sender.setImage(new UIImage(nsData), UIControlState.Normal);
		}
	}

	@Override
	public void openTextInputField (final NativeInputConfiguration configuration) {
		configuration.validate();
		if (textfield != null) throw new GdxRuntimeException("Can't open TextInputField, if KeyBoard is already open");
		createDefaultTextField(configuration.isMultiLine(),
			configuration.isMultiLine() || (configuration.getType() != OnscreenKeyboardType.Default
				&& configuration.getType() != OnscreenKeyboardType.Password));
		placeHolder = configuration.getPlaceholder();
		softkeyboardActive = true;
		inputStringValidator = configuration.getValidator();
		maxTextLength = configuration.getMaxLength();
		UITextInput uiTextInput = (UITextInput)textfield;
		textfield.setHidden(false);
		textInputWrapper = configuration.getTextInputWrapper();
		uiTextInput.setKeyboardType(getIosInputType(configuration.getType()));

		if (configuration.isPreventCorrection()) {
			uiTextInput.setAutocorrectionType(UITextAutocorrectionType.No);
			uiTextInput.setSpellCheckingType(UITextSpellCheckingType.No);
			uiTextInput.setAutocapitalizationType(UITextAutocapitalizationType.Sentences);
		} else {
			uiTextInput.setAutocorrectionType(UITextAutocorrectionType.Yes);
			uiTextInput.setSpellCheckingType(UITextSpellCheckingType.Yes);
			uiTextInput.setAutocapitalizationType(UITextAutocapitalizationType.Sentences);
		}

		if (textfield instanceof UITextView) {
			if (textInputWrapper.getText().isEmpty()) {
				((UITextView)textfield).setText(configuration.getPlaceholder());
				((UITextView)textfield).setTextColor(UIColor.lightGray());
			} else {
				((UITextView)textfield).setText(textInputWrapper.getText());
			}
			((UITextView)textfield).setDelegate(textViewDelegate);
		} else {
			final UITextField asTextField = (UITextField)textfield;
			if (configuration.getAutoComplete() != null) {
				suggestionTable = new UITableView(new CGRect(app.graphics.screenBounds.width, app.graphics.screenBounds.height,
					app.graphics.screenBounds.width, 50));
				suggestionTable.setScrollEnabled(true);
				suggestionTable.setBackgroundColor(UIColor.white());
				suggestionTable.setRowHeight(40);
				final Array<String> available = new Array<>(configuration.getAutoComplete());
				suggestionTable.setDataSource(new UITableViewDataSourceAdapter() {
					@Override
					public UITableViewCell getCellForRow (UITableView tableView, NSIndexPath indexPath) {
						UITableViewCell cell = tableView.dequeueReusableCell("suggestion");
						if (cell == null) cell = new UITableViewCell(UITableViewCellStyle.Default, "suggestion");
						if (Foundation.getMajorSystemVersion() >= 14) {
							UIListContentConfiguration contentConfiguration = cell.defaultContentConfiguration();
							NSAttributedString coloredText = new NSAttributedString(available.get(indexPath.getRow()),
								new NSDictionary<>(NSAttributedStringAttribute.ForegroundColor.value(), UIColor.white()));
							contentConfiguration.setAttributedText(coloredText);
							cell.setContentConfiguration(contentConfiguration);
						} else {
							cell.getTextLabel().setText(available.get(indexPath.getRow()));
							cell.getTextLabel().setTextColor(UIColor.black());
						}
						cell.setBackgroundColor(UIColor.white());
						return cell;
					}

					@Override
					public long getNumberOfRowsInSection (UITableView tableView, long section) {
						return available.size;
					}
				});
				suggestionTable.setDelegate(new UITableViewDelegateAdapter() {
					@Override
					public void didSelectRow (UITableView tableView, NSIndexPath indexPath) {
						tableView.deselectRow(indexPath, true);
						asTextField.setText(available.get(indexPath.getRow()));
						Gdx.input.closeTextInputField(false);
					}
				});

				asTextField.addTarget(new NSObject() {
					@Method(selector = "changedText")
					public void changedText (UITextField textField) {
						available.clear();
						for (String s : configuration.getAutoComplete()) {
							if (s.startsWith(textField.getText())) {
								available.add(s);
							}
						}
						int height = (int)(available.size * suggestionTable.getRowHeight());
						CGRect textFrame = textfield.getFrame();
						suggestionTable.setFrame(new CGRect(new CGPoint(textFrame.getX(), textFrame.getY() - height),
							new CGSize(textFrame.getWidth(), height)));

						suggestionTable.reloadData();

					}
				}, Selector.register("changedText"), UIControlEvents.EditingChanged);
				app.getUIViewController().getView().addSubview(suggestionTable);
			}
			asTextField.setText(textInputWrapper.getText());
			asTextField.setDelegate(textDelegate);
			// Because apple seems to have unreadable placeholder color by default
			NSAttributedString placeholderString = new NSAttributedString(configuration.getPlaceholder(),
				new NSDictionary<>(NSAttributedStringAttribute.ForegroundColor.value(), UIColor.lightGray()));
			asTextField.setAttributedPlaceholder(placeholderString);

			if (configuration.getType() == OnscreenKeyboardType.Password) {
				UIButton button = new UIButton(UIButtonType.Custom);
				UtilityCallback utilityCallback = new UtilityCallback();
				utilityCallback.togglePasswordView(button);
				button.setImageEdgeInsets(new UIEdgeInsets(0, -16, 0, 0));
				button.setFrame(new CGRect(new CGPoint(textfield.getFrame().getSize().getWidth() - 25, 5), new CGSize(25, 25)));
				button.addTarget(utilityCallback, Selector.register("togglePasswordView"), UIControlEvents.TouchUpInside);
				asTextField.setRightView(button);
				asTextField.setRightViewMode(UITextFieldViewMode.Always);
			}
		}
		textfield.reloadInputViews();
		textfield.becomeFirstResponder();

		UITextPosition start = uiTextInput.getPosition(uiTextInput.getBeginningOfDocument(), textInputWrapper.getSelectionStart());
		UITextPosition end = uiTextInput.getPosition(uiTextInput.getBeginningOfDocument(), textInputWrapper.getSelectionEnd());

		uiTextInput.setSelectedTextRange(uiTextInput.getTextRange(start, end));
	}

	@Override
	public void closeTextInputField (final boolean sendReturn) {
		if (textfield == null) return;
		UITextInput uiTextInput = (UITextInput)textfield;
		softkeyboardActive = false;
		String text;
		if (textfield instanceof UITextView) {
			text = ((UITextView)textfield).getText();
			if (((UITextView)textfield).getTextColor().isEqual(UIColor.lightGray())) text = "";
		} else {
			text = ((UITextField)textfield).getText();
		}
		final long position = uiTextInput.getOffset(uiTextInput.getBeginningOfDocument(),
			uiTextInput.getSelectedTextRange().getStart());
		final String finalText = text;
		Gdx.app.postRunnable(new Runnable() {
			TextInputWrapper wrapper = textInputWrapper;

			@Override
			public void run () {
				wrapper.setText(finalText);
				wrapper.setPosition((int)position);
				if (sendReturn) {
					inputProcessor.keyDown(Keys.ENTER);
					inputProcessor.keyTyped((char)13);
				}
			}
		});

		if (suggestionTable != null) {
			for (NSObject action : ((UITextField)textfield).getAllTargets()) {
				if (action != null) {
					((UITextField)textfield).removeTarget(action, Selector.register("changedText"), UIControlEvents.EditingChanged);
				}
			}
			suggestionTable.removeFromSuperview();
			suggestionTable = null;
		}

		textfield.resignFirstResponder();
		// We could first move the text field animated down and than delete, but I think it doesn't matter
		textfield.removeFromSuperview();
		textfield = null;
		inputStringValidator = null;
		textInputWrapper = null;

	}

	@Override
	public void setKeyboardHeightObserver (KeyboardHeightObserver observer) {
		app.graphics.viewController.observer = observer;
	}

	protected UIKeyboardType getIosInputType (OnscreenKeyboardType type) {
		UIKeyboardType preferredInputType;
		switch (type) {
		case NumberPad:
			preferredInputType = UIKeyboardType.NumberPad;
			break;
		case PhonePad:
			preferredInputType = UIKeyboardType.PhonePad;
			break;
		case Email:
			preferredInputType = UIKeyboardType.EmailAddress;
			break;
		case URI:
			preferredInputType = UIKeyboardType.URL;
			break;
		case Password: // no equivalent in UIKeyboardType?
		default:
			preferredInputType = UIKeyboardType.Default;
			break;
		}
		return preferredInputType;
	}

	/** Set the keyboard to close when the UITextField return key is pressed
	 * @param shouldClose Whether or not the keyboard should clsoe on return key press */
	public void setKeyboardCloseOnReturnKey (boolean shouldClose) {
		keyboardCloseOnReturn = shouldClose;
	}

	public UIView getActiveKeyboardTextField () {
		return textfield;
	}

	private void createDefaultTextField (boolean isMultiLine, boolean needsDoneToolbar) {
		CGRect rect = new CGRect();
		rect.setOrigin(new CGPoint(app.graphics.screenBounds.width, app.graphics.screenBounds.height));
		rect.setSize(new CGSize(app.graphics.screenBounds.width, 50));

		UIToolbar uiToolbar = null;
		if (needsDoneToolbar) {
			uiToolbar = new UIToolbar(
				new CGRect(new CGPoint(0, 0), new CGSize(UIScreen.getMainScreen().getBounds().getSize().getWidth(), 35)));

			UIBarButtonItem space = new UIBarButtonItem(UIBarButtonSystemItem.FlexibleSpace, (NSObject)null, null);

			UIBarButtonItem doneButton = new UIBarButtonItem(UIBarButtonSystemItem.Done, new UtilityCallback(),
				Selector.register("doneClicked"));
			uiToolbar.setItems(new NSArray<>(space, doneButton));
			uiToolbar.updateConstraintsIfNeeded();
		}

		if (isMultiLine) {
			UITextView textView = new UITextView(rect);
			textView.setInputAccessoryView(uiToolbar);
			textView.setTextColor(UIColor.black());
			textView.setReturnKeyType(UIReturnKeyType.Default);
			textfield = textView;
		} else {
			UITextField uiTextField = new UITextField(rect);
			uiTextField.setTextColor(UIColor.black());
			uiTextField.setReturnKeyType(UIReturnKeyType.Done);
			uiTextField.setInputAccessoryView(uiToolbar);
			textfield = uiTextField;
		}

		UITextInputTraits asTrait = (UITextInputTraits)textfield;
		// Parameters
		// Setting parameters
		asTrait.setKeyboardType(UIKeyboardType.Default);
		asTrait.setAutocapitalizationType(UITextAutocapitalizationType.None);
		asTrait.setAutocorrectionType(UITextAutocorrectionType.Yes);
		asTrait.setSpellCheckingType(UITextSpellCheckingType.Yes);
		textfield.setHidden(true);
		textfield.setBackgroundColor(UIColor.white());

		app.getUIViewController().getView().addSubview(textfield);
	}

	/** Builds an {@link UIAlertController} with an added {@link UITextField} for inputting text.
	 * @param listener Text input listener
	 * @param title Dialog title
	 * @param text Text for text field
	 * @param type
	 * @return UIAlertController */
	private UIAlertController buildUIAlertController (final TextInputListener listener, String title, final String text,
		final String placeholder, final OnscreenKeyboardType type) {
		final UIAlertController uiAlertController = new UIAlertController(title, text, UIAlertControllerStyle.Alert);
		uiAlertController.addTextField(new VoidBlock1<UITextField>() {
			@Override
			public void invoke (UITextField uiTextField) {
				uiTextField.setPlaceholder(placeholder);
				uiTextField.setText(text);
				uiTextField.setKeyboardType(getIosInputType(type));
				if (type == OnscreenKeyboardType.Password) {
					uiTextField.setSecureTextEntry(true);
				}

			}
		});
		uiAlertController.addAction(new UIAlertAction("Ok", UIAlertActionStyle.Default, new VoidBlock1<UIAlertAction>() {
			@Override
			public void invoke (UIAlertAction uiAlertAction) {
				// user clicked "Ok" button
				UITextField textField = uiAlertController.getTextFields().get(0);
				listener.input(textField.getText());
			}
		}));
		uiAlertController.addAction(new UIAlertAction("Cancel", UIAlertActionStyle.Cancel, new VoidBlock1<UIAlertAction>() {
			@Override
			public void invoke (UIAlertAction uiAlertAction) {
				// user clicked "Cancel" button
				listener.canceled();
			}
		}));
		return uiAlertController;
	}

	@Override
	public void vibrate (int milliseconds) {
		haptics.vibrate(milliseconds, true);
	}

	@Override
	public void vibrate (int milliseconds, boolean fallback) {
		haptics.vibrate(milliseconds, fallback);
	}

	@Override
	public void vibrate (int milliseconds, int amplitude, boolean fallback) {
		haptics.vibrate(milliseconds, amplitude, fallback);
	}

	@Override
	public void vibrate (VibrationType vibrationType) {
		haptics.vibrate(vibrationType);
	}

	@Override
	public long getCurrentEventTime () {
		return currentEventTimeStamp;
	}

	@Override
	public void setInputProcessor (InputProcessor processor) {
		this.inputProcessor = processor;
	}

	@Override
	public InputProcessor getInputProcessor () {
		return inputProcessor;
	}

	@Override
	public boolean isPeripheralAvailable (Peripheral peripheral) {
		if (peripheral == Peripheral.Accelerometer && config.useAccelerometer) return true;
		if (peripheral == Peripheral.MultitouchScreen) return true;
		if (peripheral == Peripheral.Vibrator) return haptics.isVibratorSupported();
		if (peripheral == Peripheral.HapticFeedback) return haptics.isHapticsSupported();
		if (peripheral == Peripheral.Compass) return compassSupported;
		if (peripheral == Peripheral.OnscreenKeyboard) return true;
		if (peripheral == Peripheral.Pressure) return pressureSupported;
		if (peripheral == Peripheral.HardwareKeyboard)
			return Foundation.getMajorSystemVersion() >= 14 ? GCKeyboard.getCoalescedKeyboard() != null : hadHardwareKeyEvent;
		return false;
	}

	@Override
	public int getRotation () {
		// we measure orientation counter clockwise, just like on Android
		switch (app.uiApp.getStatusBarOrientation()) {
		case LandscapeLeft:
			return 270;
		case PortraitUpsideDown:
			return 180;
		case LandscapeRight:
			return 90;
		case Portrait:
		default:
			return 0;
		}
	}

	@Override
	public Orientation getNativeOrientation () {
		return Orientation.Portrait;
	}

	@Override
	public void setCursorCatched (boolean catched) {
	}

	@Override
	public boolean isCursorCatched () {
		return false;
	}

	@Override
	public void setCursorPosition (int x, int y) {
	}

	@Override
	public void onTouch (long touches) {
		toTouchEvents(touches);
		Gdx.graphics.requestRendering();
	}

	@Override
	public boolean onKey (UIKey key, boolean down) {
		if (key == null) {
			return false;
		}

		int keyCode = getGdxKeyCode(key);

		if (keyCode != Keys.UNKNOWN) synchronized (keyEvents) {
			hadHardwareKeyEvent = true;

			KeyEvent event = keyEventPool.obtain();
			long timeStamp = System.nanoTime();
			event.timeStamp = timeStamp;
			event.keyChar = 0;
			event.keyCode = keyCode;
			event.type = down ? KeyEvent.KEY_DOWN : KeyEvent.KEY_UP;
			keyEvents.add(event);

			if (!down) {
				char character;

				switch (keyCode) {
				case Keys.DEL:
					character = 8;
					break;
				case Keys.FORWARD_DEL:
					character = 127;
					break;
				case Keys.ENTER:
					character = 13;
					break;
				default:
					String characters = key.getCharacters();
					// special keys return constants like "UIKeyInputF5", so we check for length 1
					character = (characters != null && characters.length() == 1) ? characters.charAt(0) : 0;
				}

				if (character >= 0) {
					event = keyEventPool.obtain();
					event.timeStamp = timeStamp;
					event.type = KeyEvent.KEY_TYPED;
					event.keyCode = keyCode;
					event.keyChar = character;
					keyEvents.add(event);
				}

				if (pressedKeys[keyCode]) {
					pressedKeyCount--;
					pressedKeys[keyCode] = false;
				}
			} else {
				if (!pressedKeys[event.keyCode]) {
					pressedKeyCount++;
					pressedKeys[event.keyCode] = true;
				}
			}

		}

		return isCatchKey(keyCode);
	}

	@Override
	public void processEvents () {
		synchronized (touchEvents) {
			justTouched = false;
			for (TouchEvent event : touchEvents) {
				currentEventTimeStamp = event.timestamp;
				switch (event.phase) {
				case Began:
					if (inputProcessor != null) inputProcessor.touchDown(event.x, event.y, event.pointer, Buttons.LEFT);
					if (numTouched >= 1) justTouched = true;
					break;
				case Ended:
					if (inputProcessor != null) inputProcessor.touchUp(event.x, event.y, event.pointer, Buttons.LEFT);
					break;
				case Cancelled:
					if (inputProcessor != null) inputProcessor.touchCancelled(event.x, event.y, event.pointer, Buttons.LEFT);
					break;
				case Moved:
				case Stationary:
					if (inputProcessor != null) inputProcessor.touchDragged(event.x, event.y, event.pointer);
					break;
				}
			}
			touchEventPool.freeAll(touchEvents);
			touchEvents.clear();
		}

		synchronized (keyEvents) {
			if (keyJustPressed) {
				keyJustPressed = false;
				for (int i = 0; i < justPressedKeys.length; i++) {
					justPressedKeys[i] = false;
				}
			}

			for (KeyEvent e : keyEvents) {
				currentEventTimeStamp = e.timeStamp;
				switch (e.type) {
				case KeyEvent.KEY_DOWN:
					if (inputProcessor != null) inputProcessor.keyDown(e.keyCode);
					keyJustPressed = true;
					justPressedKeys[e.keyCode] = true;
					break;
				case KeyEvent.KEY_UP:
					if (inputProcessor != null) inputProcessor.keyUp(e.keyCode);
					break;
				case KeyEvent.KEY_TYPED:
					// don't process key typed events if soft keyboard is active
					// the soft keyboard hook already catches the changes
					if (!softkeyboardActive && inputProcessor != null) inputProcessor.keyTyped(e.keyChar);
				}

			}
			keyEventPool.freeAll(keyEvents);
			keyEvents.clear();
		}
	}

	private int getFreePointer () {
		for (int i = 0; i < touchDown.length; i++) {
			if (touchDown[i] == 0) return i;
		}
		throw new GdxRuntimeException("Couldn't find free pointer id!");
	}

	private int findPointer (UITouch touch) {
		long ptr = touch.getHandle();
		for (int i = 0; i < touchDown.length; i++) {
			if (touchDown[i] == ptr) return i;
		}
		// If pointer is not found
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < touchDown.length; i++) {
			sb.append(i + ":" + touchDown[i] + " ");
		}
		Gdx.app.error("IOSInput", "Pointer ID lookup failed: " + ptr + ", " + sb.toString());
		return POINTER_NOT_FOUND;
	}

	private static class NSSetExtensions extends NSExtensions {
		@Method(selector = "allObjects")
		public static native @Pointer long allObjects (@Pointer long thiz);
	}

	private static class NSArrayExtensions extends NSExtensions {
		@Method(selector = "objectAtIndex:")
		public static native @Pointer long objectAtIndex$ (@Pointer long thiz, @MachineSizedUInt long index);

		@Method(selector = "count")
		public static native @MachineSizedUInt long count (@Pointer long thiz);
	}

	private void toTouchEvents (long touches) {
		long array = NSSetExtensions.allObjects(touches);
		int length = (int)NSArrayExtensions.count(array);
		final IOSScreenBounds screenBounds = app.getScreenBounds();
		for (int i = 0; i < length; i++) {
			long touchHandle = NSArrayExtensions.objectAtIndex$(array, i);
			UITouch touch = UI_TOUCH_WRAPPER.wrap(touchHandle);
			int locX, locY;
			// Get and map the location to our drawing space
			{
				CGPoint loc = touch.getLocationInView(app.graphics.view);
				if (config.hdpiMode == HdpiMode.Pixels) {
					locX = (int)((loc.getX() - screenBounds.x) * app.pixelsPerPoint);
					locY = (int)((loc.getY() - screenBounds.y) * app.pixelsPerPoint);
				} else {
					locX = (int)(loc.getX() - screenBounds.x);
					locY = (int)(loc.getY() - screenBounds.y);
				}
				// app.debug("IOSInput","pos= "+loc+" bounds= "+bounds+" x= "+locX+" locY= "+locY);
			}

			// if its not supported, we will simply use 1.0f when touch is present
			float pressure = 1.0f;
			if (pressureSupported) {
				pressure = (float)touch.getForce();
			}

			synchronized (touchEvents) {
				UITouchPhase phase = touch.getPhase();
				TouchEvent event = touchEventPool.obtain();
				event.x = locX;
				event.y = locY;
				event.phase = phase;
				event.timestamp = (long)(touch.getTimestamp() * 1000000000);

				if (phase == UITouchPhase.Began) {
					event.pointer = getFreePointer();
					touchDown[event.pointer] = touch.getHandle();
					touchX[event.pointer] = event.x;
					touchY[event.pointer] = event.y;
					deltaX[event.pointer] = 0;
					deltaY[event.pointer] = 0;
					pressures[event.pointer] = pressure;
					numTouched++;
				}

				else if (phase == UITouchPhase.Moved || phase == UITouchPhase.Stationary) {
					event.pointer = findPointer(touch);
					if (event.pointer != POINTER_NOT_FOUND) {
						deltaX[event.pointer] = event.x - touchX[event.pointer];
						deltaY[event.pointer] = event.y - touchY[event.pointer];
						touchX[event.pointer] = event.x;
						touchY[event.pointer] = event.y;
						pressures[event.pointer] = pressure;
					}
				}

				else if (phase == UITouchPhase.Cancelled || phase == UITouchPhase.Ended) {
					event.pointer = findPointer(touch);
					if (event.pointer != POINTER_NOT_FOUND) {
						touchDown[event.pointer] = 0;
						touchX[event.pointer] = event.x;
						touchY[event.pointer] = event.y;
						deltaX[event.pointer] = 0;
						deltaY[event.pointer] = 0;
						pressures[event.pointer] = 0;
						numTouched--;
					}
				}

				if (event.pointer != POINTER_NOT_FOUND) {
					touchEvents.add(event);
				} else {
					touchEventPool.free(event);
				}
			}
		}
	}

	static class TouchEvent {
		UITouchPhase phase;
		long timestamp;
		int x, y;
		int pointer;
	}

	static class KeyEvent {
		static final int KEY_DOWN = 0;
		static final int KEY_UP = 1;
		static final int KEY_TYPED = 2;

		long timeStamp;
		int type;
		int keyCode;
		char keyChar;
	}

	@Override
	public float getGyroscopeX () {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public float getGyroscopeY () {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public float getGyroscopeZ () {
		// TODO Auto-generated method stub
		return 0;
	}

	protected int getGdxKeyCode (UIKey key) {
		UIKeyboardHIDUsage keyCode;
		try {
			keyCode = key.getKeyCode();
		} catch (IllegalArgumentException e) {
			return Keys.UNKNOWN;
		}

		switch (keyCode) {
		case KeyboardA:
			return Keys.A;
		case KeyboardB:
			return Keys.B;
		case KeyboardC:
			return Keys.C;
		case KeyboardD:
			return Keys.D;
		case KeyboardE:
			return Keys.E;
		case KeyboardF:
			return Keys.F;
		case KeyboardG:
			return Keys.G;
		case KeyboardH:
			return Keys.H;
		case KeyboardI:
			return Keys.I;
		case KeyboardJ:
			return Keys.J;
		case KeyboardK:
			return Keys.K;
		case KeyboardL:
			return Keys.L;
		case KeyboardM:
			return Keys.M;
		case KeyboardN:
			return Keys.N;
		case KeyboardO:
			return Keys.O;
		case KeyboardP:
			return Keys.P;
		case KeyboardQ:
			return Keys.Q;
		case KeyboardR:
			return Keys.R;
		case KeyboardS:
			return Keys.S;
		case KeyboardT:
			return Keys.T;
		case KeyboardU:
			return Keys.U;
		case KeyboardV:
			return Keys.V;
		case KeyboardW:
			return Keys.W;
		case KeyboardX:
			return Keys.X;
		case KeyboardY:
			return Keys.Y;
		case KeyboardZ:
			return Keys.Z;
		case Keyboard1:
			return Keys.NUM_1;
		case Keyboard2:
			return Keys.NUM_2;
		case Keyboard3:
			return Keys.NUM_3;
		case Keyboard4:
			return Keys.NUM_4;
		case Keyboard5:
			return Keys.NUM_5;
		case Keyboard6:
			return Keys.NUM_6;
		case Keyboard7:
			return Keys.NUM_7;
		case Keyboard8:
			return Keys.NUM_8;
		case Keyboard9:
			return Keys.NUM_9;
		case Keyboard0:
			return Keys.NUM_0;
		case KeyboardReturnOrEnter:
			return Keys.ENTER;
		case KeyboardEscape:
			return Keys.ESCAPE;
		case KeyboardDeleteOrBackspace:
			return Keys.BACKSPACE;
		case KeyboardTab:
			return Keys.TAB;
		case KeyboardSpacebar:
			return Keys.SPACE;
		case KeyboardHyphen:
			return Keys.MINUS;
		case KeyboardEqualSign:
			return Keys.EQUALS;
		case KeyboardOpenBracket:
			return Keys.LEFT_BRACKET;
		case KeyboardCloseBracket:
			return Keys.RIGHT_BRACKET;
		case KeyboardBackslash:
			return Keys.BACKSLASH;
		case KeyboardNonUSPound:
			return Keys.POUND;
		case KeyboardSemicolon:
			return Keys.SEMICOLON;
		case KeyboardQuote:
			return Keys.APOSTROPHE;
		case KeyboardGraveAccentAndTilde:
			return Keys.GRAVE;
		case KeyboardComma:
			return Keys.COMMA;
		case KeyboardPeriod:
			return Keys.PERIOD;
		case KeyboardSlash:
			return Keys.SLASH;
		case KeyboardF1:
			return Keys.F1;
		case KeyboardF2:
			return Keys.F2;
		case KeyboardF3:
			return Keys.F3;
		case KeyboardF4:
			return Keys.F4;
		case KeyboardF5:
			return Keys.F5;
		case KeyboardF6:
			return Keys.F6;
		case KeyboardF7:
			return Keys.F7;
		case KeyboardF8:
			return Keys.F8;
		case KeyboardF9:
			return Keys.F9;
		case KeyboardF10:
			return Keys.F10;
		case KeyboardF11:
			return Keys.F11;
		case KeyboardF12:
			return Keys.F12;
		case KeyboardF13:
			return Keys.F13;
		case KeyboardF14:
			return Keys.F14;
		case KeyboardF15:
			return Keys.F15;
		case KeyboardF16:
			return Keys.F16;
		case KeyboardF17:
			return Keys.F17;
		case KeyboardF18:
			return Keys.F18;
		case KeyboardF19:
			return Keys.F19;
		case KeyboardF20:
			return Keys.F20;
		case KeyboardF21:
			return Keys.F21;
		case KeyboardF22:
			return Keys.F22;
		case KeyboardF23:
			return Keys.F23;
		case KeyboardF24:
			return Keys.F24;
		case KeyboardPause:
			return Keys.PAUSE;
		case KeyboardInsert:
			return Keys.INSERT;
		case KeyboardHome:
			return Keys.HOME;
		case KeyboardPageUp:
			return Keys.PAGE_UP;
		case KeyboardDeleteForward:
			return Keys.FORWARD_DEL;
		case KeyboardEnd:
			return Keys.END;
		case KeyboardPageDown:
			return Keys.PAGE_DOWN;
		case KeyboardRightArrow:
			return Keys.RIGHT;
		case KeyboardLeftArrow:
			return Keys.LEFT;
		case KeyboardDownArrow:
			return Keys.DOWN;
		case KeyboardUpArrow:
			return Keys.UP;
		case KeypadNumLock:
			return Keys.NUM_LOCK;
		case KeypadSlash:
			return Keys.NUMPAD_DIVIDE;
		case KeypadAsterisk:
			return Keys.NUMPAD_MULTIPLY;
		case KeypadHyphen:
			return Keys.NUMPAD_SUBTRACT;
		case KeypadPlus:
			return Keys.NUMPAD_ADD;
		case KeypadEnter:
			return Keys.NUMPAD_ENTER;
		case Keypad1:
			return Keys.NUM_1;
		case Keypad2:
			return Keys.NUM_2;
		case Keypad3:
			return Keys.NUM_3;
		case Keypad4:
			return Keys.NUM_4;
		case Keypad5:
			return Keys.NUM_5;
		case Keypad6:
			return Keys.NUM_6;
		case Keypad7:
			return Keys.NUM_7;
		case Keypad8:
			return Keys.NUM_8;
		case Keypad9:
			return Keys.NUM_9;
		case Keypad0:
			return Keys.NUM_0;
		case KeypadPeriod:
			return Keys.NUMPAD_DOT;
		case KeyboardNonUSBackslash:
			return Keys.BACKSLASH;
		case KeyboardApplication:
			return Keys.MENU;
		case KeyboardPower:
			return Keys.POWER;
		case KeypadEqualSign:
		case KeypadEqualSignAS400:
			return Keys.NUMPAD_EQUALS;
		case KeyboardHelp:
			return Keys.F1;
		case KeyboardMenu:
			return Keys.MENU;
		case KeyboardSelect:
			return Keys.BUTTON_SELECT;
		case KeyboardStop:
			return Keys.MEDIA_STOP;
		case KeyboardFind:
			return Keys.SEARCH;
		case KeyboardMute:
			return Keys.MUTE;
		case KeyboardVolumeUp:
			return Keys.VOLUME_UP;
		case KeyboardVolumeDown:
			return Keys.VOLUME_DOWN;
		case KeypadComma:
			return Keys.NUMPAD_COMMA;
		case KeyboardAlternateErase:
			return Keys.DEL;
		case KeyboardCancel:
			return Keys.ESCAPE;
		case KeyboardClear:
			return Keys.CLEAR;
		case KeyboardReturn:
			return Keys.ENTER;
		case KeyboardLeftControl:
			return Keys.CONTROL_LEFT;
		case KeyboardLeftShift:
			return Keys.SHIFT_LEFT;
		case KeyboardLeftAlt:
			return Keys.ALT_LEFT;
		case KeyboardRightControl:
			return Keys.CONTROL_RIGHT;
		case KeyboardRightShift:
			return Keys.SHIFT_RIGHT;
		case KeyboardRightAlt:
			return Keys.ALT_RIGHT;
		case KeyboardCapsLock:
			return Keys.CAPS_LOCK;
		case KeyboardPrintScreen:
			return Keys.PRINT_SCREEN;
		case KeyboardScrollLock:
			return Keys.SCROLL_LOCK;
		default:
			return Keys.UNKNOWN;
		}
	}
}
