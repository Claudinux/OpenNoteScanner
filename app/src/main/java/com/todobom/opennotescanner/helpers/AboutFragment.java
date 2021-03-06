package com.todobom.opennotescanner.helpers;

import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.DialogFragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import com.todobom.opennotescanner.R;

import us.feras.mdv.MarkdownView;

/**
 * Created by allgood on 20/02/16.
 */
public class AboutFragment extends DialogFragment {

    private Runnable mRunOnDetach;

    public AboutFragment() {

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View aboutView = inflater.inflate(R.layout.about_view, container);
        getDialog().getWindow().requestFeature(Window.FEATURE_NO_TITLE);

        return aboutView;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Get field from view
        // Fetch arguments from bundle and set title
        /*
        String title = getArguments().getString("title", "Enter Name");
        getDialog().setTitle(title);
        */

        MarkdownView markdownView = (MarkdownView) view.findViewById(R.id.about_markdown);

        markdownView.loadMarkdownFile("file:///android_asset/" + getString(R.string.about_filename));

        /*

        StringBuilder aboutBuffer=new StringBuilder();
        BufferedReader in;
        String aboutString="";
        try {
            InputStream aboutStream=getContext().getAssets().open(getString(R.string.about_filename));
            in = new BufferedReader(new InputStreamReader(aboutStream, "UTF-8"));
            while ((aboutString=in.readLine()) != null) {
                aboutBuffer.append(aboutString + "\n");
            }
            aboutString = aboutBuffer.toString();
            in.close();

            markdownView.setStringContent(aboutString);

        } catch (IOException e) {
            e.printStackTrace();
        }

        /* */

    }

    @Override
    public void onDetach() {
        super.onDetach();
        if (mRunOnDetach != null) {
            mRunOnDetach.run();
        }
    }

    public void setRunOnDetach( Runnable runOnDetach ) {
        mRunOnDetach = runOnDetach;
    }
}
