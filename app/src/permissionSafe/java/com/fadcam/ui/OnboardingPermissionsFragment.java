package com.fadcam.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.fadcam.R;
import com.github.appintro.SlidePolicy;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Production-safe onboarding permission gate.
 *
 * The protected upstream implementation required storage, notification and
 * battery-exemption state before leaving onboarding. That contract is invalid
 * for the hardened manifest: Android 10+ gives a camera app access to media it
 * owns without storage permission, READ_MEDIA_VIDEO is deliberately removed,
 * and direct battery-optimization exemption is deliberately removed.
 *
 * Camera and microphone are the only mandatory runtime permissions for the
 * recording experience. Optional storage/media access and power-management
 * choices must never deadlock the onboarding wizard.
 */
public class OnboardingPermissionsFragment extends Fragment implements SlidePolicy {
    private static final int PERMISSIONS_REQUEST_CODE = 101;

    private boolean permissionsGranted;
    private boolean permissionRequestStarted;
    private MaterialButton grantButton;
    private TextView permissionStatusText;
    private boolean statusToastShown;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.onboarding_permissions_slide, container, false);

        grantButton = view.findViewById(R.id.permissions_grant_button);
        permissionStatusText = view.findViewById(R.id.permission_status_text);
        if (permissionStatusText != null) {
            permissionStatusText.setVisibility(View.GONE);
        }

        // The legacy slide visually presented storage as a permission. It is
        // not a mandatory runtime permission on Android 10+ for media owned by
        // this app, so remove that misleading affordance from the active UI.
        LinearLayout permissionsList = view.findViewById(R.id.permissionsListContainer);
        if (permissionsList != null && permissionsList.getChildCount() >= 3) {
            permissionsList.getChildAt(2).setVisibility(View.GONE);
        }

        // Battery-optimization exemption is intentionally not part of the
        // production manifest. Do not present it as an onboarding requirement.
        View batteryButton = view.findViewById(R.id.disable_battery_optimization_button);
        if (batteryButton != null) {
            batteryButton.setVisibility(View.GONE);
        }
        View andText = view.findViewById(R.id.and_text);
        if (andText != null) {
            andText.setVisibility(View.GONE);
        }

        if (grantButton != null) {
            grantButton.setOnClickListener(v -> requestMissingPermissions());
        }

        View openSettingsLink = view.findViewById(R.id.open_settings_link);
        if (openSettingsLink != null) {
            openSettingsLink.setOnClickListener(v -> openAppSettings());
        }

        checkPermissionsAndUpdateUI();

        // Preserve the original onboarding convenience of prompting immediately,
        // but only ever request permissions that are actually required and
        // declared by the application.
        view.post(() -> {
            if (isAdded() && !permissionsGranted) {
                requestMissingPermissions();
            }
        });

        return view;
    }

    private List<String> getRequiredPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        permissions.add(Manifest.permission.RECORD_AUDIO);
        return permissions;
    }

    private List<String> getMissingRequiredPermissions() {
        List<String> missing = new ArrayList<>();
        Context context = getContext();
        if (context == null) return missing;

        for (String permission : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(context, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        return missing;
    }

    private void requestMissingPermissions() {
        if (!isAdded()) return;

        List<String> missing = getMissingRequiredPermissions();
        if (missing.isEmpty()) {
            checkPermissionsAndUpdateUI();
            return;
        }

        permissionRequestStarted = true;
        requestPermissions(missing.toArray(new String[0]), PERMISSIONS_REQUEST_CODE);
    }

    private boolean areRequiredPermissionsGranted() {
        return getMissingRequiredPermissions().isEmpty();
    }

    private boolean hasPermanentlyDeniedRequiredPermission() {
        if (!isAdded() || !permissionRequestStarted || getActivity() == null) return false;

        for (String permission : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(requireContext(), permission)
                    != PackageManager.PERMISSION_GRANTED
                    && !ActivityCompat.shouldShowRequestPermissionRationale(getActivity(), permission)) {
                return true;
            }
        }
        return false;
    }

    private void checkPermissionsAndUpdateUI() {
        if (!isAdded()) return;

        permissionsGranted = areRequiredPermissionsGranted();

        if (grantButton != null) {
            grantButton.setEnabled(!permissionsGranted);
            grantButton.setAlpha(permissionsGranted ? 0.5f : 1f);
            grantButton.setText(permissionsGranted
                    ? R.string.permissions_granted
                    : R.string.grant_permissions);
        }

        if (permissionsGranted) {
            showPermissionStatus(R.string.permissions_granted, true);
        } else if (hasPermanentlyDeniedRequiredPermission()) {
            showPermissionStatus(R.string.permissions_note, false);
        }
    }

    private void showPermissionStatus(int stringResId, boolean success) {
        if (!isAdded()) return;

        if (permissionStatusText != null) {
            permissionStatusText.setText(stringResId);
            permissionStatusText.setTextColor(ContextCompat.getColor(requireContext(),
                    success ? R.color.green : R.color.redPastel));
            permissionStatusText.setVisibility(View.VISIBLE);
        } else if (!statusToastShown) {
            Toast.makeText(requireContext(), stringResId, Toast.LENGTH_SHORT).show();
            statusToastShown = true;
        }
    }

    private void openAppSettings() {
        if (!isAdded()) return;
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
        startActivity(intent);
    }

    @Override
    public void onResume() {
        super.onResume();
        checkPermissionsAndUpdateUI();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            checkPermissionsAndUpdateUI();
        }
    }

    @Override
    public boolean isPolicyRespected() {
        return permissionsGranted;
    }

    @Override
    public void onUserIllegallyRequestedNextPage() {
        if (!permissionsGranted) {
            showPermissionStatus(R.string.permissions_note, false);
            requestMissingPermissions();
        }
    }

    /** Refresh permission-related labels after the onboarding language changes. */
    public void refreshLanguage() {
        View view = getView();
        if (view == null || !isAdded()) return;

        TextView titleText = view.findViewById(R.id.permissionsTitle);
        if (titleText != null) titleText.setText(R.string.permissions_required);

        TextView descText = view.findViewById(R.id.permissionsDescription);
        if (descText != null) descText.setText(R.string.permissions_description);

        if (grantButton != null) {
            grantButton.setText(permissionsGranted
                    ? R.string.permissions_granted
                    : R.string.grant_permissions);
        }

        TextView settingsLink = view.findViewById(R.id.open_settings_link);
        if (settingsLink != null) settingsLink.setText(R.string.open_settings);

        TextView noteText = view.findViewById(R.id.permissionsNote);
        if (noteText != null) noteText.setText(R.string.permissions_note);

        LinearLayout permissionsListContainer = view.findViewById(R.id.permissionsListContainer);
        if (permissionsListContainer != null && permissionsListContainer.getChildCount() >= 3) {
            setPermissionLabel(permissionsListContainer.getChildAt(0), R.string.onboarding_camera);
            setPermissionLabel(permissionsListContainer.getChildAt(1), R.string.onboarding_microphone);
            permissionsListContainer.getChildAt(2).setVisibility(View.GONE);
        }

        TextView andText = view.findViewById(R.id.and_text);
        if (andText != null) andText.setVisibility(View.GONE);

        MaterialButton batteryButton = view.findViewById(R.id.disable_battery_optimization_button);
        if (batteryButton != null) batteryButton.setVisibility(View.GONE);

        TextView orText = view.findViewById(R.id.or_text);
        if (orText != null) orText.setText(R.string.onboarding_or);
    }

    private void setPermissionLabel(View item, int textResId) {
        if (!(item instanceof LinearLayout)) return;
        LinearLayout row = (LinearLayout) item;
        if (row.getChildCount() < 2) return;
        View child = row.getChildAt(1);
        if (child instanceof TextView) ((TextView) child).setText(textResId);
    }
}
